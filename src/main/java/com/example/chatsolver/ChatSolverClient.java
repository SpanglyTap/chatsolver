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
 *   .solver                    toggle on/off
 *   .solver delay 1000 2500    set reply delay range in ms
 *   .solver debug              show what the mod detected (for fixing patterns)
 */
public class ChatSolverClient implements ClientModInitializer {

    static int minDelayMs = 1200;
    static int maxDelayMs = 2800;
    static boolean enabled = true;
    static boolean debug = false;

    static final Solver SOLVER = new Solver();
    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatsolver");
        t.setDaemon(true);
        return t;
    });
    static final Random RNG = new Random();
    static String lastMessage = "";
    static long lastHandledAt = 0;

    @Override
    public void onInitializeClient() {
        try (InputStream mc = ChatSolverClient.class.getResourceAsStream("/mc_words.txt");
             InputStream dict = ChatSolverClient.class.getResourceAsStream("/words.txt")) {
            SOLVER.load(mc, dict);
        } catch (Exception e) { e.printStackTrace(); }

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
            } else if (a.length >= 2 && a[1].equalsIgnoreCase("debug")) {
                debug = !debug;
                info("ChatSolver debug: " + (debug ? "ON" : "OFF"));
            } else {
                enabled = !enabled;
                info("ChatSolver: " + (enabled ? "ON" : "OFF"));
            }
            return false;
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) handle(msg.getString()); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> handle(msg.getString()));
    }

    static void handle(String message) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        // ignore the same line arriving twice, and don't answer again right after answering
        if (message.equals(lastMessage) && now - lastHandledAt < 4000) return;
        String answer;
        try { answer = SOLVER.solve(message); } catch (Exception e) { answer = null; }
        if (answer == null || answer.isEmpty()) return;
        if (now - lastHandledAt < 1500) return;
        lastMessage = message;
        lastHandledAt = now;
        if (debug) info("ChatSolver: \"" + message + "\"  ->  " + answer);
        send(answer);
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
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }

    // ===== SOLVER-START (pure Java, no Minecraft code) =====
    static class Solver {
        final Map<String, List<String>> anagrams = new HashMap<>();
        final List<String> mcPhrases = new ArrayList<>();
        final List<String> dictWords = new ArrayList<>();

        static final Pattern TOKEN = Pattern.compile("[A-Za-z_]+");
        static final Pattern FILL_KEY = Pattern.compile("(?i)fill|gap|missing|complete|blank");
        static final Pattern REVERSE_KEY = Pattern.compile("(?i)revers\\w*");
        static final Pattern SCRAMBLE_KEY = Pattern.compile("(?i)scrambl\\w*");
        static final Pattern MATH_A = Pattern.compile("(?i)(?:solve|calculate|compute|math)[^0-9\\-(]*([0-9(\\-][0-9+\\-*/x×÷().\\s]*[0-9)])");
        static final Pattern MATH_B = Pattern.compile("(?i)what is\\s*([0-9(\\-][0-9+\\-*/x×÷().\\s]*[0-9)])");

        void load(InputStream mc, InputStream dict) throws Exception {
            if (mc != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(mc, StandardCharsets.UTF_8));
                String w;
                while ((w = r.readLine()) != null) {
                    w = w.trim().toLowerCase();
                    if (w.length() < 3) continue;
                    mcPhrases.add(w);
                    String[] parts = w.split(" ");
                    if (parts.length == 1) index(w, w);
                    else {
                        for (String p : parts) if (p.length() > 2) index(p, p);
                        index(w.replace(" ", ""), w); // whole phrase scrambled together
                    }
                }
            }
            if (dict != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(dict, StandardCharsets.UTF_8));
                String w;
                while ((w = r.readLine()) != null) {
                    w = w.trim();
                    if (w.length() > 2) { dictWords.add(w); index(w, w); }
                }
            }
        }

        void index(String letters, String answer) {
            List<String> l = anagrams.computeIfAbsent(sortLetters(letters), k -> new ArrayList<>(1));
            if (!l.contains(answer)) l.add(answer);
        }

        static String sortLetters(String s) {
            char[] c = s.toCharArray();
            Arrays.sort(c);
            return new String(c);
        }

        static String caps(String t) {
            StringBuilder sb = new StringBuilder();
            for (String w : t.split(" ")) {
                if (w.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return sb.toString();
        }

        static final Pattern TYPE_KEY = Pattern.compile("(?i)\\btype\\s+(?:the\\s+)?(?:word|words|phrase|text|number)\\b[:\\s]*(.*)$");

        String currentMessage = "";
        long pendingAt = 0;

        String solve(String message) {
            currentMessage = message;
            String a;
            String low = message.toLowerCase();
            // ignore player chat (the server uses " » ") and win/result announcements
            if (message.indexOf('»') >= 0) return null;
            if (low.contains(" typed ") || low.contains("won the chat game") || low.contains("you won")) return null;
            if ((a = typeTheWord(message)) != null) return a;
            if (message.indexOf('_') >= 0 && (a = fillGap(message)) != null) return caps(a);
            Matcher r = REVERSE_KEY.matcher(message);
            if (r.find()) { String p = payload(message, r.end()); if (p != null) return new StringBuilder(p).reverse().toString(); }
            Matcher s = SCRAMBLE_KEY.matcher(message);
            if (s.find()) { String p = payload(message, s.end()); if (p != null && (a = unscramble(p)) != null) return caps(a); }
            return math(message);
        }

        /** "Type the word" game: answer is the text shown, exactly as shown. The text may be on the next line or the next message. */
        String typeTheWord(String message) {
            String[] lines = message.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = TYPE_KEY.matcher(lines[i].trim());
                if (!m.find()) continue;
                String ans = m.group(1).trim();
                if (ans.isEmpty()) {
                    for (int j = i + 1; j < lines.length && ans.isEmpty(); j++) ans = lines[j].trim();
                }
                if (!ans.isEmpty()) { pendingAt = 0; return ans; }
                pendingAt = System.currentTimeMillis(); // answer should arrive in the next message
                return null;
            }
            if (pendingAt > 0) {
                long age = System.currentTimeMillis() - pendingAt;
                String t = message.trim();
                if (age < 3000 && !t.isEmpty() && !t.equalsIgnoreCase("chat game") && t.split("\\s+").length <= 6) {
                    pendingAt = 0;
                    return t;
                }
                if (age >= 3000) pendingAt = 0;
            }
            return null;
        }

        /** Text the game wants us to work on: after the last ':' or '>' following the keyword, else the last word. */
        static String payload(String msg, int from) {
            String rest = msg.substring(from);
            int c = Math.max(rest.lastIndexOf(':'), Math.max(rest.lastIndexOf('>'), rest.lastIndexOf('»')));
            String p;
            if (c >= 0) p = rest.substring(c + 1);
            else {
                String[] w = rest.trim().split("\\s+");
                if (msg.trim().split("\\s+").length > 8) return null; // long sentence = probably normal chat
                p = w.length == 0 ? "" : w[w.length - 1];
            }
            p = p.trim().replaceAll("^[\"'`*\\[(<]+|[\"'`*\\])>!?.,]+$", "").trim();
            return p.isEmpty() ? null : p;
        }

        String unscramble(String p) {
            String[] toks = p.toLowerCase().split("\\s+");
            StringBuilder out = new StringBuilder();
            boolean all = true;
            for (String t : toks) {
                List<String> m = anagrams.get(sortLetters(t));
                if (m == null || m.isEmpty()) { all = false; break; }
                if (out.length() > 0) out.append(' ');
                out.append(m.get(0));
            }
            if (all) return out.toString();
            List<String> m = anagrams.get(sortLetters(p.toLowerCase().replace(" ", "")));
            return (m == null || m.isEmpty()) ? null : m.get(0);
        }

        String fillGap(String message) {
            List<String> toks = new ArrayList<>();
            Matcher m = TOKEN.matcher(message);
            while (m.find()) toks.add(m.group().toLowerCase());
            String best = null;
            int bestScore = -1;
            for (int i = 0; i < toks.size(); i++) {
                for (int n = 1; n <= 5 && i + n <= toks.size(); n++) {
                    List<String> win = toks.subList(i, i + n);
                    int us = 0;
                    for (String t : win) if (t.indexOf('_') >= 0) us++;
                    if (us == 0) continue;
                    String hit = matchPhrase(win);
                    if (hit == null) continue;
                    int score = us * 10 - n;
                    if (score > bestScore) { bestScore = score; best = hit; }
                }
            }
            return best;
        }

        String matchPhrase(List<String> win) {
            for (String ph : mcPhrases) {
                String[] parts = ph.split(" ");
                if (parts.length != win.size()) continue;
                boolean ok = true;
                for (int k = 0; k < parts.length && ok; k++) ok = wordMatches(win.get(k), parts[k]);
                if (ok) return ph;
            }
            if (win.size() == 1 && FILL_KEY.matcher(currentMessage).find()) {
                for (String w : dictWords) if (wordMatches(win.get(0), w.toLowerCase())) return w.toLowerCase();
            }
            return null;
        }

        static boolean wordMatches(String pattern, String word) {
            if (pattern.length() != word.length()) return false;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '_' && c != word.charAt(i)) return false;
            }
            return true;
        }

        String math(String message) {
            Matcher m = MATH_A.matcher(message);
            if (!m.find()) { m = MATH_B.matcher(message); if (!m.find()) return null; }
            try {
                String e = m.group(1).replace('x', '*').replace('×', '*').replace('÷', '/');
                double v = new Parser(e).parse();
                if (Double.isNaN(v) || Double.isInfinite(v)) return null;
                return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100.0) / 100.0);
            } catch (Exception e) { return null; }
        }
    }

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
    // ===== SOLVER-END =====
}
