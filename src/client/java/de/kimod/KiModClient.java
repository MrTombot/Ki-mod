package de.kimod;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class KiModClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private static final String SYSTEM =
            "Du bist ein freundlicher Begleiter in Minecraft Java. "
            + "Antworte auf Deutsch, kurz (höchstens 5 Sätze) und ohne Markdown.";
    private static final int MAX_HISTORY = 20;

    private final List<JsonObject> history = new ArrayList<>();
    private boolean busy = false;
    private final List<String> chat = new ArrayList<>(); // Verlauf für die Anzeige
    private String bubble = "";
    private long bubbleUntil = 0;

    @Override
    public void onInitializeClient() {
        loadConfig(); // legt die Datei beim ersten Start an

        // Taste K öffnet den Chat (in den Steuerungs-Einstellungen änderbar)
        KeyMapping openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ki-mod.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_K,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ki-mod", "keys"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null) client.gui.setScreen(new ChatPanel());
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ki-mod", "bubble"),
                (graphics, delta) -> drawBubble(graphics));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("ki")
                    .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ask(StringArgumentType.getString(ctx, "text"), false);
                                return 1;
                            })));

            dispatcher.register(ClientCommands.literal("kibild")
                    .executes(ctx -> {
                        ask("Was siehst du auf diesem Screenshot?", true);
                        return 1;
                    })
                    .then(ClientCommands.argument("frage", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ask(StringArgumentType.getString(ctx, "frage"), true);
                                return 1;
                            })));

            dispatcher.register(ClientCommands.literal("kireset")
                    .executes(ctx -> {
                        history.clear();
                        chat.clear();
                        bubbleUntil = 0;
                        ctx.getSource().sendFeedback(Component.literal("[KI] Gespräch gelöscht."));
                        return 1;
                    }));
        });
    }

    private boolean ask(String text, boolean withImage) {
        if (busy) {
            say("Moment, ich antworte noch.");
            return false;
        }
        JsonObject config = loadConfig();
        String key = config.has("apiKey") ? config.get("apiKey").getAsString().trim() : "";
        String model = config.has("model") ? config.get("model").getAsString().trim() : "gemini-flash-latest";
        if (key.isEmpty()) {
            say("Kein API-Key. Trag ihn in config/ki-mod.json ein.");
            return false;
        }

        busy = true;
        chat.add("Du: " + text + (withImage ? " (mit Screenshot)" : ""));
        List<JsonObject> oldMessages = new ArrayList<>(history);

        CompletableFuture
                .supplyAsync(() -> request(key, model, oldMessages, text, withImage))
                .whenComplete((answer, error) -> Minecraft.getInstance().execute(() -> {
                    busy = false;
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        say("Fehler: " + cause.getMessage());
                        return;
                    }
                    history.add(message("user", text));
                    history.add(message("model", answer));
                    while (history.size() > MAX_HISTORY) history.remove(0);
                    say(answer);
                }));
        return true;
    }

    private static String request(String key, String model, List<JsonObject> oldMessages,
                                  String text, boolean withImage) {
        try {
            JsonArray contents = new JsonArray();
            oldMessages.forEach(contents::add);

            JsonObject current = message("user", text);
            if (withImage) {
                Path shot = newestScreenshot();
                JsonObject data = new JsonObject();
                data.addProperty("mime_type", "image/png");
                data.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(shot)));
                JsonObject part = new JsonObject();
                part.add("inline_data", data);
                current.getAsJsonArray("parts").add(part);
            }
            contents.add(current);

            JsonObject body = new JsonObject();
            body.add("system_instruction", message(null, SYSTEM));
            body.add("contents", contents);

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
                            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 429) {
                throw new IOException("Limit erreicht, versuch es später nochmal.");
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " (Key und Modellname prüfen)");
            }
            return readAnswer(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen");
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private static String readAnswer(String json) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        StringBuilder result = new StringBuilder();
        if (root.has("candidates")) {
            JsonObject first = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
            if (first.has("content") && first.getAsJsonObject("content").has("parts")) {
                for (JsonElement element : first.getAsJsonObject("content").getAsJsonArray("parts")) {
                    JsonObject part = element.getAsJsonObject();
                    if (part.has("text")) result.append(part.get("text").getAsString());
                }
            }
        }
        if (result.isEmpty()) throw new IOException("Die KI hat keine Antwort geschickt.");
        return result.toString().replace("*", "").trim();
    }

    private static Path newestScreenshot() throws IOException {
        Path folder = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        if (!Files.isDirectory(folder)) throw new IOException("Kein Screenshot da. Drück erst F2.");
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.toString().endsWith(".png"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IOException("Kein Screenshot da. Drück erst F2."));
        }
    }

    private static JsonObject message(String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject message = new JsonObject();
        if (role != null) message.addProperty("role", role);
        message.add("parts", parts);
        return message;
    }

    private static JsonObject loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("ki-mod.json");
        try {
            if (!Files.exists(file)) {
                JsonObject standard = new JsonObject();
                standard.addProperty("apiKey", "");
                standard.addProperty("model", "gemini-flash-latest");
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(standard));
            }
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[KI-Mod] Config nicht lesbar: " + e.getMessage());
            return new JsonObject();
        }
    }

    private void say(String text) {
        chat.add("KI: " + text);
        while (chat.size() > 100) chat.remove(0);
        bubble = text;
        bubbleUntil = System.currentTimeMillis() + 12_000;
    }

    /** Sprechblase oben rechts im Spiel. */
    private void drawBubble(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof ChatPanel) return;
        boolean showAnswer = System.currentTimeMillis() < bubbleUntil;
        if (!busy && !showAnswer) return;

        Font font = client.font;
        String text = busy ? ".".repeat(1 + (int) (System.currentTimeMillis() / 400 % 3)) : bubble;
        List<FormattedCharSequence> lines = font.split(Component.literal(text), 140);
        int shown = Math.min(lines.size(), 8);
        int width = 152;
        int height = shown * 10 + 10;
        int x = graphics.guiWidth() - width - 6;
        int y = 6;

        graphics.fill(x + 2, y, x + width - 2, y + height, 0xF0FFFFFF);
        graphics.fill(x, y + 2, x + width, y + height - 2, 0xF0FFFFFF);
        for (int i = 0; i < 5; i++) { // Zipfel der Blase
            graphics.fill(x + width - 20 + i, y + height + i, x + width - 12, y + height + i + 1, 0xF0FFFFFF);
        }
        for (int i = 0; i < shown; i++) {
            graphics.text(font, lines.get(i), x + 6, y + 6 + i * 10, 0xFF202020, false);
        }
        if (lines.size() > shown) {
            graphics.text(font, "[K]", x + width - 20, y + height - 11, 0xFF2060C0, false);
        }
    }

    /** Fenster am rechten Rand: Verlauf und Eingabezeile. */
    private class ChatPanel extends Screen {
        private EditBox input;
        private int left;
        private int panelWidth;
        private int scroll = 0; // Zeilen über dem Ende

        ChatPanel() {
            super(Component.literal("KI-Chat"));
        }

        @Override
        protected void init() {
            String draft = input == null ? "" : input.getValue();
            panelWidth = Math.min(200, width - 20);
            left = width - panelWidth;

            input = new EditBox(font, left + 6, height - 48, panelWidth - 12, 18, Component.literal("Nachricht"));
            input.setMaxLength(500);
            input.setValue(draft);
            addRenderableWidget(input);
            setInitialFocus(input);

            int half = (panelWidth - 16) / 2;
            addRenderableWidget(Button.builder(Component.literal("Senden"), b -> send(false))
                    .bounds(left + 6, height - 26, half, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+ Screenshot"), b -> send(true))
                    .bounds(left + 10 + half, height - 26, half, 20).build());
        }

        private void send(boolean withImage) {
            String text = input.getValue().trim();
            if (text.isEmpty() && withImage) text = "Was siehst du auf diesem Screenshot?";
            if (text.isEmpty()) return;
            if (ask(text, withImage)) {
                input.setValue("");
                scroll = 0;
            }
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isConfirmation() && getFocused() == input) {
                send(false);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            scroll = Math.max(0, scroll + (int) Math.signum(scrollY) * 3);
            return true;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(left, 0, width, height, 0xD0101018);
            graphics.text(font, "KI-Chat", left + 6, 6, 0xFFFFFFFF, true);

            List<FormattedCharSequence> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (String message : chat) {
                int color = message.startsWith("Du:") ? 0xFF8FD0FF : 0xFFFFFFFF;
                for (FormattedCharSequence line : font.split(Component.literal(message), panelWidth - 12)) {
                    lines.add(line);
                    colors.add(color);
                }
                lines.add(FormattedCharSequence.EMPTY);
                colors.add(color);
            }
            if (busy) {
                lines.add(Component.literal("KI schreibt ...").getVisualOrderText());
                colors.add(0xFFA0A0A0);
            }

            int visible = Math.max(1, (height - 74) / 10);
            scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
            int end = lines.size() - scroll;
            int start = Math.max(0, end - visible);
            for (int i = start; i < end; i++) {
                graphics.text(font, lines.get(i), left + 6, 20 + (i - start) * 10, colors.get(i), false);
            }
            super.extractRenderState(graphics, mouseX, mouseY, delta); // Eingabefeld und Knöpfe
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            // kein Abdunkeln, die Welt bleibt sichtbar
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
        }
