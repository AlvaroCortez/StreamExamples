package org.examples.stream;

import java.util.HashMap;

public class CodeExamples {
    //will not properly work with ... parameters
    public static final HashMap<String, String> classToFileMap = new HashMap<>() {
        {
            put("java.util.stream.Collectors.groupingBy1", "/examples/Collectors.groupingBy.html");
            put("java.util.stream.Collectors.groupingBy2", "/examples/Collectors.groupingBy.html");
            put("java.util.stream.Collectors.groupingBy3", "/examples/Collectors.groupingBy.html");
            put("org.springframework.boot.SpringApplication.run2", "/examples/SpringApplication.run.html");
            put("java.util.stream.Stream.flatMap1", "/examples/Stream.flatMap1.html");
        }
    };
}
