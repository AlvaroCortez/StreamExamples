package org.examples.stream;

import java.util.HashMap;

public class CodeExamples {
    //will not properly work with ... parameters
    //and with same count of arguments but different types
    public static final HashMap<String, String> classToFileMap = new HashMap<>() {
        {
            put("java.util.stream.Collectors.groupingBy1", "/examples/Collectors/Collectors.groupingBy.html");
            put("java.util.stream.Collectors.groupingBy2", "/examples/Collectors/Collectors.groupingBy.html");
            put("java.util.stream.Collectors.groupingBy3", "/examples/Collectors/Collectors.groupingBy.html");
            put("java.util.stream.Stream.flatMap1", "/examples/Stream/Stream.flatMap1.html");
            put("java.util.stream.Stream.map1", "/examples/Stream/Stream.map1.html");
            put("java.util.stream.Stream.allMatch1", "/examples/Stream/Stream.allMatch1.html");
            put("java.util.stream.Stream.anyMatch1", "/examples/Stream/Stream.anyMatch1.html");
            put("java.util.stream.Stream.noneMatch1", "/examples/Stream/Stream.noneMatch1.html");
        }
    };
}
