package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scope names must match what the bundled VS Code grammars declare, or the editor silently
 * falls back to plain text with nothing to say why.
 */
class RemoteTextLanguageTest {

    @Test
    fun `yml and yaml are yaml`() {
        assertEquals("source.yaml", textMateScopeFor("a.yml", ""))
        assertEquals("source.yaml", textMateScopeFor("a.yaml", ""))
    }

    @Test
    fun `Dockerfile is dockerfile`() {
        assertEquals("source.dockerfile", textMateScopeFor("Dockerfile", ""))
    }

    @Test
    fun `bashrc is shell`() {
        assertEquals("source.shell", textMateScopeFor(".bashrc", ""))
    }

    @Test
    fun `a python shebang with no extension is python`() {
        assertEquals("source.python", textMateScopeFor("deploy", "#!/usr/bin/env python3"))
    }

    @Test
    fun `a shell shebang with no extension is shell`() {
        assertEquals("source.shell", textMateScopeFor("deploy", "#!/bin/bash -e"))
        assertEquals("source.shell", textMateScopeFor("deploy", "#!/usr/bin/env zsh"))
    }

    @Test
    fun `a txt file is plain text`() {
        assertNull(textMateScopeFor("notes.txt", ""))
    }

    @Test
    fun `a shebang naming an unknown interpreter is plain text`() {
        assertNull(textMateScopeFor("run", "#!/usr/bin/perl"))
    }
}
