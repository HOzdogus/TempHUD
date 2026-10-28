package com.hozdogus.temphud;

// Runs inside Shizuku with the same rights as ADB, so it can read stats
// that Android hides from normal apps.
interface IStatsService {
    // Shizuku calls this transaction code to shut the service down.
    void destroy() = 16777114;

    // Total frames the screen has drawn so far, or -1 if unavailable. Used for FPS.
    int frameCount() = 1;

    // Contents of a system file, or null if it can't be read.
    String readFile(String path) = 2;

    // Output of a shell command, or null on failure.
    String run(String command) = 3;
}
