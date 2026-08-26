package com.tracecare.backend.common.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper()));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(
                new GenericJackson2JsonRedisSerializer(redisObjectMapper()));
        return template;
    }

    /**
     * {@code GenericJackson2JsonRedisSerializer()}(무인자 생성자)는 내부적으로 자체 {@code ObjectMapper}를 만들어 다형성
     * 타입 정보(`@class`)를 자동으로 붙여주지만, {@code java.time.Instant} 등 JSR-310 타입은 별도 모듈 등록 없이는 직렬화하지
     * 못한다("Java 8 date/time type not supported by default", 실제 캐시 저장 중 발생 확인). {@code
     * ObjectMapper}를 직접 넘기는 생성자를 쓰면 무인자 생성자가 자동으로 해주던 다형성 타입 처리(`@class`)가 더 이상 자동 적용되지 않으므로 여기서 직접
     * 재현한다.
     *
     * <p>처음엔 {@code DefaultTyping.NON_FINAL}로 재현했는데, Java {@code record}(예:
     * domain.location.service.LocationCacheStore.CachedLocation)는 언어 차원에서 항상 final이라 "final 클래스는 정적
     * 타입만으로 런타임 타입을 알 수 있으니 태그가 필요 없다"는 NON_FINAL의 규칙에 걸려 `@class`가 아예 안 붙는 문제를 실제로 겪었다 ("missing
     * type id property '@class'"). Redis에서 다시 읽을 때는 정적 타입 정보가 전혀 없는 {@code Object}로 역직렬화하므로 이 힌트가
     * 반드시 필요해, record 여부와 무관하게 항상 태그를 붙이는 {@code DefaultTyping.EVERYTHING}으로 바꿨다.
     *
     * <p><b>보안: {@code LaissezFaireSubTypeValidator} → {@code BasicPolymorphicTypeValidator}로 교체
     * (2026-08)</b>. {@code DefaultTyping.EVERYTHING}은 캐시 값 어디에나 `@class` 타입 정보를 광범위하게 새겨 넣는데,
     * {@code LaissezFaireSubTypeValidator}(무제한 허용)와 결합하면 Redis에 저장된 값이 조작될 경우 `@class`에 임의의 클래스명을
     * 넣어 역직렬화 시점에 위험한 클래스를 로드시키는 가젯 체인 공격의 여지가 생긴다 — 애초에 이 Serializer를 JSON 방식으로 택한 이유(Java 역직렬화 가젯
     * 체인 공격 회피)와 정면으로 배치된다. {@link BasicPolymorphicTypeValidator}로 바꿔 역직렬화 허용 대상을 우리 프로젝트
     * 패키지({@code com.tracecare.backend})와, 실제로 캐시 값에 등장하는 JDK 타입(java.time.Instant,
     * java.util.ArrayList, java.lang.Long 등 — 직접 캐시된 값을 덤프해 확인)으로만 제한한다. 화이트리스트 밖 클래스명이 `@class`에
     * 들어오면 역직렬화 자체를 거부한다.
     *
     * <p><b>발견/수정(2026-08, AI 예측 도메인 세션)</b>: {@code java.math.}가 화이트리스트에 없어, {@code BigDecimal}
     * 필드(AI 예측 확률 등)를 담은 값을 캐시에 쓴 뒤 다시 읽으면 항상 {@code SerializationException}으로 거부되고 있었다 — 처음
     * 화이트리스트를 만들 때 그 시점까지 캐시된 값에는 {@code BigDecimal} 필드가 없어 놓쳤던 케이스다. Place 같은 좌표/반경 필드도 서비스 계층에서
     * {@code Double}로 변환해 캐시하고 있어 지금까지 드러나지 않았다.
     */
    private ObjectMapper redisObjectMapper() {
        PolymorphicTypeValidator polymorphicTypeValidator =
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.tracecare.backend.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.lang.")
                        .allowIfSubType("java.math.")
                        .build();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                polymorphicTypeValidator,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return objectMapper;
    }
}
