package com.example.chatsolver;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Controls (type in chat):
 *   .solver               toggle on/off
 *   .solver delay 1000 2500   set reply delay range in ms
 */
public class ChatSolverClient implements ClientModInitializer {

    // ===== CONFIG: adjust these to match OneBlocky's real chat game messages =====
    static final Pattern UNSCRAMBLE = Pattern.compile("(?i)unscramble.*?[:>]\\s*([a-z]+)");
    static final Pattern MATH = Pattern.compile("(?i)(?:solve|calculate|math).*?[:>]\\s*([0-9+\\-*/x×().\\s]+)");
    static int minDelayMs = 1200;
    static int maxDelayMs = 2800;
    // =============================================================================

    static boolean enabled = true;
    static final Map<String, List<String>> WORDS = new HashMap<>();
    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatsolver");
        t.setDaemon(true);
        return t;
    });
    static final Random RNG = new Random();

    @Override
    public void onInitializeClient() {
        loadWordList();

        // Toggle / settings through chat commands starting with ".solver"
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String m = message.trim();
            if (!m.toLowerCase().startsWith(".solver")) return true;
            String[] a = m.split("\\s+");
            if (a.length >= 4 && a[1].equalsIgnoreCase("delay")) {
                try {
                    minDelayMs = Integer.parseInt(a[2]);
                    maxDelayMs = Math.max(minDelayMs, Integer.parseInt(a[3]));
                    info("ChatSolver delay: " + minDelayMs + "-" + maxDelayMs + " ms");
                } catch (NumberFormatException e) {
                    info("Usage: .solver delay <minMs> <maxMs>");
                }
            } else {
                enabled = !enabled;
                info("ChatSolver: " + (enabled ? "ON" : "OFF"));
            }
            return false; // don't send this to the server
        });

        // Servers send chat games either as system messages or as chat; listen to both
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) handle(msg.getString()); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> handle(msg.getString()));
    }

    static void handle(String message) {
        if (!enabled) return;

        Matcher u = UNSCRAMBLE.matcher(message);
        if (u.find()) {
            String scrambled = u.group(1).toLowerCase();
            List<String> matches = WORDS.get(sortLetters(scrambled));
            if (matches != null && !matches.isEmpty()) send(matches.get(0));
            return;
        }

        Matcher m = MATH.matcher(message);
        if (m.find()) {
            try {
                double result = new Parser(m.group(1).replace('x', '*').replace('×', '*')).parse();
                send(result == Math.floor(result) ? String.valueOf((long) result) : String.valueOf(result));
            } catch (Exception ignored) {}
        }
    }

    static void send(String answer) {
        long delay = minDelayMs + RNG.nextInt(Math.max(1, maxDelayMs - minDelayMs));
        SCHEDULER.schedule(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null && mc.player.connection != null) mc.player.connection.sendChat(answer);
            });
        }, delay, TimeUnit.MILLISECONDS);
    }

    static void info(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
    }

    /** Word list is bundled inside the jar (common words first, so they win ties). */
    static void loadWordList() {
        try (InputStream in = ChatSolverClient.class.getResourceAsStream("/words.txt")) {
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String w;
            while ((w = r.readLine()) != null) {
                w = w.trim();
                if (w.length() > 2) WORDS.computeIfAbsent(sortLetters(w), k -> new ArrayList<>(1)).add(w);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    static String sortLetters(String s) {
        char[] c = s.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    // ---- Tiny math parser: + - * / and parentheses ----
    static class Parser {
        final String s; int pos = -1, ch;
        Parser(String s) { this.s = s; }
        void next() { ch = (++pos < s.length()) ? s.charAt(pos) : -1; }
        boolean eat(int c) { while (ch == ' ') next(); if (ch == c) { next(); return true; } return false; }
        double parse() { next(); double v = expr(); if (pos < s.length()) throw new RuntimeException(); return v; }
        double expr() { double v = term(); for (;;) { if (eat('+')) v += term(); else if (eat('-')) v -= term(); else return v; } }
        double term() { double v = factor(); for (;;) { if (eat('*')) v *= factor(); else if (eat('/')) v /= factor(); else return v; } }
        double factor() {
            if (eat('-')) return -factor();
            double v;
            if (eat('(')) { v = expr(); eat(')'); }
            else {
                while (ch == ' ') next();
                int start = pos;
                while ((ch >= '0' && ch <= '9') || ch == '.') next();
                v = Double.parseDouble(s.substring(start, pos));
            }
            return v;
        }
    }
}
