package io.github.swarnimbandekar.knightlingo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class StockfishEngine implements Closeable {
    record Result(String move, String variation, int depth) {}
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>(1024);
    private final Process process;
    private final OutputStreamWriter input;
    private volatile boolean closed;

    StockfishEngine(File executable) throws IOException, InterruptedException {
        process = new ProcessBuilder(executable.getAbsolutePath()).redirectErrorStream(true).start();
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = output.readLine()) != null) {
                    if (!lines.offer(line)) { lines.poll(); lines.offer(line); }
                }
            } catch (IOException ignored) { }
        }, "Stockfish-Output");
        reader.setDaemon(true);
        reader.start();
        try {
            command("uci");
            await("uciok", 12000);
            command("setoption name Threads value 2");
            command("setoption name Hash value 32");
            command("setoption name Skill Level value 20");
            command("setoption name UCI_LimitStrength value false");
            command("isready");
            await("readyok", 12000);
        } catch (IOException | InterruptedException error) {
            close();
            throw error;
        }
    }

    Result analyze(String fen) throws IOException, InterruptedException {
        lines.clear();
        command("position fen " + fen);
        command("go movetime 1000");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        String variation = "";
        int depth = 0;
        while (!closed && System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if (line.startsWith("bestmove ")) return new Result(line.split(" ")[1], variation, depth);
            if (line.startsWith("info depth ") && line.contains(" pv ")) {
                String[] words = line.split(" ");
                depth = Integer.parseInt(words[2]);
                variation = line.substring(line.indexOf(" pv ") + 4);
            }
        }
        throw new IOException("Stockfish search timed out");
    }

    private void command(String line) throws IOException {
        if (closed) throw new IOException("Engine closed");
        input.write(line + "\n");
        input.flush();
    }

    private void await(String token, int timeoutMs) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!closed && System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (token.equals(line)) return;
            if (!process.isAlive() && lines.isEmpty()) throw new IOException("Stockfish exited before " + token);
        }
        throw new IOException("Stockfish initialization timed out: " + token);
    }

    @Override public void close() {
        closed = true;
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
        try { input.close(); } catch (IOException ignored) { }
    }
}
