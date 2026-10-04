package com.example.rkfund;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import org.bson.types.ObjectId;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serializes BSON ids as their canonical hexadecimal value across every API response. */
@Configuration
public class JacksonConfig {
    @Bean
    JsonMapperBuilderCustomizer objectIdAsString() {
        SimpleModule module = new SimpleModule("mongo-object-id-string");
        module.addSerializer(ObjectId.class, new StdSerializer<ObjectId>(ObjectId.class) {
            @Override
            public void serialize(ObjectId value, JsonGenerator generator, SerializationContext context) {
                generator.writeString(value.toHexString());
            }
        });
        return builder -> builder.addModule(module);
    }
}
