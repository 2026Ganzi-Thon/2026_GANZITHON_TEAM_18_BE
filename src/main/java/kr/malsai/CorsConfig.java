package kr.malsai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * FE(다른 주소)에서 /api/** 를 호출할 수 있도록 CORS 허용.
 * 허용 주소는 환경 변수 CORS_ALLOWED_ORIGINS로 지정 (쉼표로 구분).
 * 예) CORS_ALLOWED_ORIGINS=http://localhost:5173,https://malsai.vercel.app
 * 지정하지 않으면 모든 주소 허용 (해커톤 개발용).
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    CorsConfig(@Value("${cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
