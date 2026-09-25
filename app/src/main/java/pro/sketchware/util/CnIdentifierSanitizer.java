package pro.sketchware.util;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.yq;

/**
 * Compiles-time identifier sanitizer.
 *
 * Allows users to use Chinese (non-ASCII) names for variables, more-blocks,
 * components and view ids. Before the generated sources are handed to the
 * Java compiler, every non-ASCII identifier token in the generated .java
 * sources is deterministically mapped to a random-looking English identifier
 * (cn_xxxxxxxx). String literals and comments are left untouched, so user
 * visible Chinese text keeps working.
 *
 * The same mapping is applied to "@+id/..." and "@id/..." references inside
 * the generated XML resources, so view ids stay consistent between layout
 * XML and R.id usages in Java.
 */
public final class CnIdentifierSanitizer {

    private static final String TAG = "CnIdSanitizer";

    private CnIdentifierSanitizer() {
    }

    /** Deterministic English replacement for a user identifier. */
    public static String mapIdentifier(String original) {
        long h = 1125899906842597L;
        for (int i = 0; i < original.length(); i++) {
            h = 31 * h + original.charAt(i);
        }
        String hex = Long.toHexString(h & 0xffffffffL);
        while (hex.length() < 8) {
            hex = "0" + hex;
        }
        return "cn" + hex;
    }

    private static boolean isNonAsciiLetterOrDigit(char c) {
        return c >= 0x80 && (Character.isLetterOrDigit(c) || Character.isJavaIdentifierPart(c));
    }

    /** Replace non-ASCII identifier tokens outside of strings/comments in java source. */
    public static String sanitizeJavaSource(String src) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        int n = src.length();
        int i = 0;
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char
        while (i < n) {
            char c = src.charAt(i);
            switch (state) {
                case 0: { // code
                    if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                        state = 1;
                        out.append(c);
                        i++;
                    } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                        state = 2;
                        out.append(src.charAt(i));
                        out.append(src.charAt(i + 1));
                        i += 2;
                    } else if (c == '"') {
                        state = 3;
                        out.append(c);
                        i++;
                    } else if (c == '\'') {
                        state = 4;
                        out.append(c);
                        i++;
                    } else if (isNonAsciiLetterOrDigit(c)) {
                        // collect whole non-ascii identifier token
                        int start = i;
                        while (i < n && isNonAsciiLetterOrDigit(src.charAt(i))) {
                            i++;
                        }
                        String token = src.substring(start, i);
                        // make sure previous char isn't an identifier char (ascii part of same name)
                        boolean gluedToAscii = false;
                        if (start > 0) {
                            char prev = src.charAt(start - 1);
                            if (Character.isJavaIdentifierPart(prev)) {
                                gluedToAscii = true;
                            }
                        }
                        if (gluedToAscii) {
                            // mixed ascii+nonascii identifier: replace only the non-ascii tail
                            out.append(mapIdentifier(token));
                        } else {
                            out.append(mapIdentifier(token));
                        }
                    } else {
                        out.append(c);
                        i++;
                    }
                    break;
                }
                case 1: { // line comment
                    out.append(c);
                    if (c == '\n') {
                        state = 0;
                    }
                    i++;
                    break;
                }
                case 2: { // block comment
                    out.append(c);
                    if (c == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                        out.append('/');
                        i += 2;
                        state = 0;
                    } else {
                        i++;
                    }
                    break;
                }
                case 3: { // string literal - copy verbatim (escape aware)
                    out.append(c);
                    if (c == '\\') {
                        if (i + 1 < n) {
                            out.append(src.charAt(i + 1));
                        }
                        i += 2;
                    } else if (c == '"') {
                        state = 0;
                        i++;
                    } else {
                        i++;
                    }
                    break;
                }
                case 4: { // char literal - copy verbatim
                    out.append(c);
                    if (c == '\\') {
                        if (i + 1 < n) {
                            out.append(src.charAt(i + 1));
                        }
                        i += 2;
                    } else if (c == '\'') {
                        state = 0;
                        i++;
                    } else {
                        i++;
                    }
                    break;
                }
                default:
                    out.append(c);
                    i++;
            }
        }
        return out.toString();
    }

    private static final Pattern XML_ID_REF = Pattern.compile("(@\\+?id/)([\\p{IsHan}][\\p{IsHan}\\p{IsAlphabetic}0-9_]*)");

    /** Replace @+id/中文名 and @id/中文名 in generated XML resources. */
    public static String sanitizeXmlSource(String src) {
        Matcher m = XML_ID_REF.matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + mapIdentifier(m.group(2))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void processDirectory(File dir, boolean xml) {
        if (dir == null || !dir.exists()) {
            return;
        }
        List<File> files = new ArrayList<>();
        collect(dir, files);
        for (File f : files) {
            try {
                Path p = f.toPath();
                byte[] bytes = Files.readAllBytes(p);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String converted = xml ? sanitizeXmlSource(content) : sanitizeJavaSource(content);
                if (!converted.equals(content)) {
                    Files.write(p, converted.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.w(TAG, "sanitize failed for " + f, e);
            }
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collect(c, out);
            } else if (c.getName().endsWith(".java") || c.getName().endsWith(".xml")) {
                out.add(c);
            }
        }
    }

    /** Entry point called before java compilation. */
    public static void sanitizeProjectSources(yq project, pro.sketchware.utility.FilePathUtil fpu) {
        try {
            processDirectory(new File(project.javaFilesPath), false);
            processDirectory(new File(fpu.getPathBroadcast(project.sc_id)), false);
            processDirectory(new File(fpu.getPathService(project.sc_id)), false);
            processDirectory(new File(project.resDirectoryPath), true);
        } catch (Throwable t) {
            Log.w(TAG, "sanitizeProjectSources failed", t);
        }
    }
}
