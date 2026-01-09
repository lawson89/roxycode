package org.roxycode.core.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

@Factory
public class JavaParserProvider {

    @PostConstruct
    public void setupStaticParser() {
        StaticJavaParser.setConfiguration(createConfiguration());
    }

    @Singleton
    @Bean
    public JavaParser javaParser() {
        return new JavaParser(createConfiguration());
    }

    @Singleton
    @Bean
    public ParserConfiguration parserConfiguration() {
        return createConfiguration();
    }

    private ParserConfiguration createConfiguration() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        config.setSymbolResolver(new JavaSymbolSolver(combinedTypeSolver));
        
        return config;
    }
}
