package com.bosi.ykt.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Long 序列化为 String（规避 JS 雪花 ID 精度丢失），同时支持将 JSON string 反序列化回 Long。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class,  ToStringSerializer.instance)
                .serializerByType(Long.TYPE,   ToStringSerializer.instance)
                .deserializerByType(Long.class, new LongFromStringDeserializer(Long.class))
                .deserializerByType(Long.TYPE,  new LongFromStringDeserializer(Long.TYPE));
    }

    private static class LongFromStringDeserializer extends StdDeserializer<Long> {
        LongFromStringDeserializer(Class<?> vc) { super(vc); }

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String s = p.getValueAsString();
            if (s == null || s.isBlank()) return null;
            try { return Long.parseLong(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }
    }
}
