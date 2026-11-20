package com.example.aiplayer.bot;

/** One step of what the AI asked for. Runs on the server thread, one tick() per game tick. */
public interface Task {
    /** Short text shown to the AI as "current task". */
    String describe();

    /** @return true when finished (successfully or not - report via {@link AiBot#note}). */
    boolean tick(AiBot bot);
}
