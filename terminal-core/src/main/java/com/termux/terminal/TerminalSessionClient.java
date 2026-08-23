package com.termux.terminal;

/**
 * Trimmed down from upstream termux-app's {@code TerminalSessionClient}.
 *
 * <p>Upstream declares this as the callback interface between {@code TerminalSession} (a pty/JNI
 * class that drives a local subprocess) and its client, so most of its methods are typed in terms
 * of {@code TerminalSession}. This module vendors {@link TerminalEmulator} but deliberately does
 * not vendor {@code TerminalSession} or the pty/JNI code (see ../../../../../../VENDORING.md), so
 * this interface keeps only the members that {@link TerminalEmulator} and {@link Logger} actually
 * call: cursor style/state and the log* callbacks. Everything that referenced
 * {@code TerminalSession} has been removed.
 */
public interface TerminalSessionClient {

    void onTerminalCursorStateChange(boolean state);

    Integer getTerminalCursorStyle();

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

}
