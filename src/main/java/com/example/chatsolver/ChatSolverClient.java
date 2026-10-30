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
        static final Pattern RESULT_LINE = Pattern.compile("(?i)\\bin\\s+\\d+(?:\\.\\d+)?\\s*s\\b");
        static final Set<String> FILLER = new HashSet<>(Arrays.asList(
                "the", "this", "following", "letters", "letter", "words", "word", "text", "phrase", "below", "to", "a"));

        String currentMessage = "";
        String pendingKind = null; // game started but the text to work on hasn't arrived yet
        long pendingAt = 0;

        String solve(String message) {
            currentMessage = message;
            String a;
            String low = message.toLowerCase();
            long now = System.currentTimeMillis();
            // ignore player chat (the server uses " » "), win/result announcements and headers
            if (message.indexOf('»') >= 0) return null;
            if (low.contains(" typed ") || low.contains("won the chat game") || low.contains("you won")
                    || RESULT_LINE.matcher(message).find()) return null;
            if (message.trim().equalsIgnoreCase("chat game")) return null;

            boolean hasType = false;
            for (String ln : message.split("\\R")) if (TYPE_KEY.matcher(ln.trim()).find()) hasType = true;
            Matcher r = REVERSE_KEY.matcher(message);
            Matcher sc = SCRAMBLE_KEY.matcher(message);
            boolean hasRev = r.find();
            boolean hasScr = sc.find();

            // the text for a game that was announced in the previous message
            if (!hasType && !hasRev && !hasScr && pendingKind != null) {
                long age = now - pendingAt;
                String t = lastLine(message);
                if (age < 3000 && !t.isEmpty() && t.split("\\s+").length <= 8) {
                    String kind = pendingKind;
                    pendingKind = null;
                    t = clean(t);
                    if (kind.equals("type")) return t;
                    if (kind.equals("reverse")) return new StringBuilder(t).reverse().toString();
                    String u = unscramble(t);
                    return u == null ? null : caps(u);
                }
                if (age >= 3000) pendingKind = null;
            }

            if (hasType) {
                a = typeTheWord(message);
                if (a != null) { pendingKind = null; return a; }
                pendingKind = "type"; pendingAt = now;
                return null;
            }
            if (message.indexOf('_') >= 0 && (a = fillGap(message)) != null) return caps(a);
            if (hasRev) {
                String p = payload(message, r.end());
                if (p != null) { pendingKind = null; return new StringBuilder(p).reverse().toString(); }
                pendingKind = "reverse"; pendingAt = now;
                return null;
            }
            if (hasScr) {
                String p = payload(message, sc.end());
                if (p != null) {
                    pendingKind = null;
                    return (a = unscramble(p)) != null ? caps(a) : null;
                }
                pendingKind = "scramble"; pendingAt = now;
                return null;
            }
            return math(message);
        }

        static String lastLine(String msg) {
            String last = "";
            for (String ln : msg.split("\\R")) if (!ln.trim().isEmpty()) last = ln.trim();
            return last;
        }

        static String clean(String p) {
            return p.trim().replaceAll("^[\"'`*\\[(<]+|[\"'`*\\])>!?.,]+$", "").trim();
        }

        /** "Type the word" game: answer is the text shown, exactly as shown (same line or the next line). */
        String typeTheWord(String message) {
            String[] lines = message.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = TYPE_KEY.matcher(lines[i].trim());
                if (!m.find()) continue;
                String ans = m.group(1).trim();
                if (ans.isEmpty()) {
                    for (int j = i + 1; j < lines.length && ans.isEmpty(); j++) ans = lines[j].trim();
                }
                return ans.isEmpty() ? null : ans;
            }
            return null;
        }

        /** The text to work on after the keyword: the last line below it, else after ':' or '>', else the rest of the line. */
        static String payload(String msg, int from) {
            String rest = msg.substring(from);
            String[] lines = rest.split("\\R");
            String last = "";
            for (int i = 1; i < lines.length; i++) if (!lines[i].trim().isEmpty()) last = lines[i].trim();
            String p;
            if (!last.isEmpty()) p = last;
            else {
                String first = lines.length > 0 ? lines[0] : "";
                int c = Math.max(first.lastIndexOf(':'), first.lastIndexOf('>'));
                if (c >= 0) p = first.substring(c + 1);
                else {
                    String[] w = first.trim().split("\\s+");
                    int k = 0;
                    while (k < w.length && FILLER.contains(w[k].toLowerCase().replaceAll("[^a-z]", ""))) k++;
                    if (w.length - k > 4) return null; // too long: probably normal chat
                    p = String.join(" ", Arrays.copyOfRange(w, k, w.length));
                }
            }
            p = clean(p);
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
