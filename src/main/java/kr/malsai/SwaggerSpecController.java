package kr.malsai;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("swagger")
@RestController
class SwaggerSpecController {
    @GetMapping(path = "/openapi/simulation.json", produces = MediaType.APPLICATION_JSON_VALUE)
    Resource simulationSpec() {
        return new ClassPathResource("swagger/simulation.json");
    }
}
