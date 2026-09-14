package kkdugi.core.serial.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kkdugi.core.serial.mapper.SerialMapper;
import kkdugi.core.serial.service.SerialService;

@Configuration
public class SerialAutoConfigurer {

    @Bean
    SerialService serialService(SerialMapper serialMapper) {
        return new SerialService(serialMapper);
    }
}
