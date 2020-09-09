/*
 * Copyright 2019-2020 Simon Dierl <simon.dierl@cs.tu-dortmund.de>
 * SPDX-License-Identifier: ISC
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
 * granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN
 * AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 * PERFORMANCE OF THIS SOFTWARE.
 */

package io.github.tudoaqua.cvc4turnkey;

import edu.stanford.CVC4.CVC4JNI;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that the loader library schedules the unpacked Z3 native libraries for deletion.
 */
public class TestShutdownHook {

    /**
     * Reflectively obtain the list of deletion-schedules files and verify that files containing {@code cvc4},
     * {@code cvc4parser}, and {@code cvc4jni} are scheduled for deletion on JVM exit after CVC4 has been invoked.
     *
     * @throws ClassNotFoundException on reflection error.
     * @throws NoSuchFieldException   on reflection error.
     * @throws IllegalAccessException on reflection error.
     */
    @Test
    public void testShutdownHookGeneration() throws
            ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        final Class<?> deleteOnExitHook = Class.forName("java.io.DeleteOnExitHook");
        final Field files = deleteOnExitHook.getDeclaredField("files");
        files.setAccessible(true);

        @SuppressWarnings("unchecked") final Set<String> before =
                new HashSet<String>((Collection<String>) files.get(null));

        CVC4JNI.Configuration_getVersionString();

        @SuppressWarnings("unchecked") final Set<String> after =
                new HashSet<String>((Collection<String>) files.get(null));
        HashSet<String> newFiles = new HashSet<>(after);
        newFiles.removeAll(before);


        assertTrue(newFiles.stream().anyMatch(file -> {
            String name = new File(file).getName();
            return name.startsWith("cvc4.") || name.startsWith("libcvc4.");
        }), "CVC4 library not scheduled for deletion " +
                "(before = " + before + ", after = " + after + ", new = " + newFiles + ")");

        assertTrue(newFiles.stream().anyMatch(file -> {
            String name = new File(file).getName();
            return name.startsWith("cvc4parser.") || name.startsWith("libcvc4parser.");
        }), "CVC4 parser library not scheduled for deletion " +
                "(before = " + before + ", after = " + after + ", new = " + newFiles + ")");

        assertTrue(newFiles.stream().anyMatch(file -> {
            String name = new File(file).getName();
            return name.startsWith("cvc4jni.") || name.startsWith("libcvc4jni.");
        }), "CVC4 native support library not scheduled for deletion " +
                "(before = " + before + ", after = " + after + ", new = " + newFiles + ")");
    }

}
