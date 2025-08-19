package de.cteichert.AIStoryWriter.service;

import de.cteichert.AIStoryWriter.configuration.ChatModelFactory;
import de.cteichert.AIStoryWriter.model.SceneDto;
import de.cteichert.AIStoryWriter.model.StoryRequest;
import de.cteichert.AIStoryWriter.model.StoryResult;
import de.cteichert.AIStoryWriter.tool.StableDiffusionTool;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StoryService {
    private static final Logger log = LoggerFactory.getLogger(StoryService.class);

    private final ChatModelFactory chatModelFactory;
    private final StableDiffusionTool stableDiffusionTool;

    private static final Pattern IMAGE_PROMPT_PATTERN =
            Pattern.compile("IMAGE_PROMPT:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Timeouts (anpassen)
    private static final Duration LLM_TIMEOUT = Duration.ofDays(30);
    private static final Duration IMAGE_TIMEOUT = Duration.ofDays(40);


    // Dedizierter Executor für blockierende Calls (steuerbar, cancel möglich)
    private final ExecutorService blockingExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("story-blocking-exec-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    // erlaubt nur einen heavy Job (LLM + Bild) gleichzeitig; setze 1 oder >1 je nach Hardware
    private final Semaphore heavyJobSemaphore = new Semaphore(1);


    public StoryService(ChatModelFactory chatModelFactory, StableDiffusionTool stableDiffusionTool) {
        this.chatModelFactory = chatModelFactory;
        this.stableDiffusionTool = stableDiffusionTool;
    }

    @PreDestroy
    public void shutdown() {
        blockingExecutor.shutdownNow();
    }

    public Mono<StoryResult> generateStory(StoryRequest request) {
        return Flux.range(1, request.scenes())
                .concatMap(i -> {
                    String promptTemplate = """
                            WICHTIG:
                            Du schreibst eigenständig eine Szene. Stelle **keine** Rückfragen an den Nutzer. Wenn dir Informationen fehlen aber halte dich stets an bisherige information der Geschichte!
                            achte darauf, dass diese auch für die nächste Szene (sofern es nicht die letzte ist) fortgeführt werden kann und dich nicht wiederholst.
                            **Sei kreativ und bring Abwechslung in die Geschichte.**
                            Schreibe Szene %d von %d im Genre %s mit Ton '%s'.
                            Derzeitige Szene ist: %s
                            
                            ***Jede Szene ergibt, wenn man alle szenen kombiniert, eine einheitliche zusammenpassende und fortlaufende Geschichte***
                            zu jeder Szene erstelle ein Bild der die Szene visualisiert, gib **genau eine einzelne Zeile** aus, beginnend mit `IMAGE_PROMPT: ` gefolgt vom ins englisch übersetzte prompt mit dem Genre/Ton.
                            
                            PRODUZIERE NUR die Szene und optional die einzelne IMAGE_PROMPT-Zeile. Antworte niemals mit Rückfragen oder TODO-Listen.
                            Berücksichtige bitte auch folgendes:
                            %s
                            """;
                    return generateScene(request, i, promptTemplate);
                })
                .collectList()
                .map(scenes -> new StoryResult(request.title(), scenes));
    }

    private Mono<SceneDto> generateScene(StoryRequest request, int sceneIndex, String promptTemplate) {
        String context = "";
        String prompt = promptTemplate.formatted(sceneIndex, request.scenes(), request.genre(), request.tone(), context, request.additonalTextPrompt());

        // Wir führen LLM + Bilderzeugung in einer synchronen Callable aus, die vorher ein globales Semaphore erwirbt.
        return Mono.fromCallable(() -> {
                    // 1) heavy-job semaphore erwerben (interruptible)
                    try {
                        log.info("Versuche heavyJobSemaphore für Szene {} zu erwerben...", sceneIndex);
                        heavyJobSemaphore.acquire();
                        log.info("heavyJobSemaphore erworben für Szene {}", sceneIndex);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for heavyJobSemaphore", ie);
                    }

                    try {
                        OpenAiChatModel model = chatModelFactory.create(request.model());

                        // 2) LLM-Call (blockierend, mit Timeout via callBlockingWithTimeout)
                        Object resp = callBlockingWithTimeout(() -> ChatClient.create(model)
                                .prompt()
                                .user(prompt)
                                .call()
                                .chatResponse(), LLM_TIMEOUT);

                        log.info("LLM antwort für Szene {} erhalten.", sceneIndex);

                        String sceneText;
                        try {
                            sceneText = extractTextFromResp(resp);
                        } catch (Exception ex) {
                            log.warn("Fehler beim Auslesen LLM-Text für Szene {}: {}", sceneIndex, ex.toString());
                            sceneText = "";
                        }

                        // 3) Prompt für Bild bestimmen (IMAGE_PROMPT oder Auto-Prompt)
                        Matcher m = IMAGE_PROMPT_PATTERN.matcher(sceneText == null ? "" : sceneText);
                        String imagePrompt = m.find() ? m.group(1).trim() : request.additonalImagePrompt() + sceneText;

                        log.info("Starte Bildgenerierung für Szene {} mit Prompt-Länge {}", sceneIndex, imagePrompt.length());

                        // 4) Bildgenerierung blocking (stableDiffusionTool.generateImageBlocking) mit Timeout
                        //    Wir rufen das innerhalb von callBlockingWithTimeout auf, damit dein existing timeout- & cancel-mechanismus greift.
                        String imagePath = callBlockingWithTimeout(() -> {
                            String negPrompt = "Bad anatomy, Low quality, incorrect object placements";
                            return stableDiffusionTool.generateImageBlocking(imagePrompt, negPrompt, 1024, 1024, IMAGE_TIMEOUT);
                        }, IMAGE_TIMEOUT);

                        log.info("Bildgenerierung komplett für Szene {} -> {}", sceneIndex, imagePath);

                        // entferne image prompt vom text
                        sceneText = sceneText.replaceAll(IMAGE_PROMPT_PATTERN.pattern(), "").trim();

                        return new SceneDto(sceneIndex, sceneText, imagePath);
                    } finally {
                        // 5) Semaphore unbedingt freigeben (auch bei Exception)
                        try {
                            heavyJobSemaphore.release();
                            log.info("heavyJobSemaphore freigegeben für Szene {}", sceneIndex);
                        } catch (Exception e) {
                            log.warn("Failed to release heavyJobSemaphore for scene {}: {}", sceneIndex, e.toString());
                        }
                    }
                })
                .subscribeOn(Schedulers.fromExecutor(blockingExecutor))
                .doOnError(err -> log.error("Fehler beim Erzeugen der Szene {}: {}", sceneIndex, err.toString()))
                .onErrorResume(e -> {
                    // fallback, wenn Timeout oder andere Fehler auftreten
                    String fallbackText = "Fehler beim Generieren der Szene: " + e.getMessage();
                    log.error("Fallback Szene für {} (Grund: {})", sceneIndex, e.toString());
                    return Mono.just(new SceneDto(sceneIndex, fallbackText, ""));
                });
    }

    /**
     * Führt einen blockierenden Callable in blockingExecutor aus und wartet max 'timeout'.
     * Bei Timeout wird Future.cancel(true) aufgerufen (wenn möglich).
     */
    private <T> T callBlockingWithTimeout(Callable<T> task, Duration timeout) throws Exception {
        Future<T> future = blockingExecutor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // versuche aktiv zu canceln / interrupten
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            // unwrappen
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new RuntimeException(cause);
            }
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    @SuppressWarnings({"rawtypes"})
    private String extractTextFromResp(Object resp) {
        switch (resp) {
            case null -> {
                return "";
            }

            // 1) Direkter String / CharSequence
            case CharSequence ignored -> {
                return resp.toString();
            }

            // 2) Map-Handling (z. B. Jackson mapped to Map)
            case java.util.Map map -> {
                // direkte Keys prüfen
                Object v;
                if ((v = map.get("text")) != null) {
                    return v.toString();
                }
                if ((v = map.get("content")) != null) {
                    return v.toString();
                }
                if ((v = map.get("message")) != null) {
                    return v.toString();
                }
                if ((v = map.get("body")) != null) {
                    return extractTextFromResp(v);
                }

                // verschachtelte Container
                if ((v = map.get("output")) != null) {
                    return extractTextFromResp(v);
                }
                if ((v = map.get("result")) != null) {
                    return extractTextFromResp(v);
                }

                // choices / messages: meist Listen mit erstem Element relevant
                if ((v = map.get("choices")) instanceof List && !((List) v).isEmpty()) {
                    return extractTextFromResp(((List) v).getFirst());
                }
                if ((v = map.get("messages")) instanceof List && !((List) v).isEmpty()) {
                    return extractTextFromResp(((List) v).getFirst());
                }
            }
            default -> {
            }
        }

        // 3) Liste => erstes Element
        if (resp instanceof List list) {
            if (list.isEmpty()) {
                return "";
            }
            return extractTextFromResp(list.getFirst());
        }

        // 4) Reflection: gängige Getter-Methoden prüfen und rekursiv auswerten
        String[] candidateMethods = {
                "getText", "text",
                "getContent", "getMessage", "getMessages",
                "getOutput", "getResult", "getBody",
                "getChoices", "getData"
        };

        for (String mName : candidateMethods) {
            try {
                java.lang.reflect.Method m = resp.getClass().getMethod(mName);
                Object o = m.invoke(resp);
                if (o != null) {
                    String extracted = extractTextFromResp(o);
                    if (extracted != null && !extracted.isBlank()) {
                        return extracted;
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // Methode nicht vorhanden → weiter
            } catch (Exception e) {
                // Falls Invocation fehlschlägt, nicht crashen, sondern weiter versuchen
            }
        }

        // 5) Versuch: Feldzugriff per Reflection (falls getter nicht vorhanden)
        try {
            java.lang.reflect.Field[] fields = resp.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                f.setAccessible(true);
                Object val = f.get(resp);
                if (val != null) {
                    String extracted = extractTextFromResp(val);
                    if (extracted != null && !extracted.isBlank()) {
                        return extracted;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 6) Letzter Ausweg: toString()
        try {
            return resp.toString();
        } catch (Exception e) {
            return "";
        }
    }
}