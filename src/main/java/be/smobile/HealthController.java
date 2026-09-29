package be.smobile;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health/status")
public class HealthController {

    @GetMapping
    public String health() {
        return "I am healthy";
    }
}
