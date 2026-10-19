package io.github.meiyongai.toki.subtitle.hook;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

/** Maintainer tool: creates public structural hashes from locally supplied reference APKs. */
public class GenerateExtensionProfile {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("source-root output-profile reference-apk...");
        Set<String> names = new TreeSet<>();
        var pattern = Pattern.compile("adaptiveClass\\(\"([^\"]+)\"\\)");
        Path root = Path.of(args[0]);
        for (String file : List.of("CaptionFallbackHook", "SubtitleFeature", "NativeSubtitleTranslator", "FeedCaptionLookAhead",
                "SubtitleMenu", "DescriptionTranslation", "DescriptionFallback", "CommentPollTranslation")) {
            var matcher = pattern.matcher(Files.readString(root.resolve(file+".kt")));
            while (matcher.find()) names.add(matcher.group(1));
        }
        names.add("X.0Gxr");
        names.add("com.ss.android.ugc.aweme.feed.assem.desc.VideoDescVM");
        var profile = ExtensionDexIndex.profile(Arrays.asList(args).subList(2, args.length), names);
        try (Writer output = Files.newBufferedWriter(Path.of(args[1]))) {
            // Properties.load(InputStream) uses ISO-8859-1; all stored data is ASCII.
            for (String key : new TreeSet<>(profile.stringPropertyNames())) {
                output.write(key.replace(":", "\\:").replace("=", "\\=") + "=" + profile.getProperty(key) + "\n");
            }
        }
        System.out.println("Reference classes="+names.size()+" profile entries="+profile.size());
    }
}
