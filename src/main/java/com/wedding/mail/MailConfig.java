package com.wedding.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MailConfig {
    /** wedding.mail.provider: "log" (default, nothing is sent) or "ses". */
    @Bean MailSender mailSender(@Value("${wedding.mail.provider:log}") String provider,
                                @Value("${wedding.mail.from:}") String from,
                                @Value("${wedding.mail.ses-region:ap-northeast-2}") String region,
                                Environment env) {
        return switch(provider) {
            case "ses" -> {
                // Fail at startup rather than on the first sign-up.
                if(!from.matches("[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+|[^<>]*<[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+>"))
                    throw new IllegalStateException("Set WEDDING_MAIL_FROM to a verified SES sender address");
                yield new SesMailSender(region,from);
            }
            case "log" -> new LogMailSender(env.matchesProfiles("demo"));
            default -> throw new IllegalStateException("Unknown wedding.mail.provider: "+provider);
        };
    }
}
