package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** application.yml's Tomcat RemoteIpValve trust list: Caddy on the Docker network yes, gateway/loopback no. */
class TrustedProxyConfigTest {

    @Test
    void onlyNonGatewayPrivateAddressesMaySetForwardedHeaders() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Pattern p = Pattern.compile(yaml.getObject().getProperty("server.tomcat.remoteip.internal-proxies"));
        for (String ok : new String[]{"172.18.0.2", "172.18.0.3", "172.31.255.10", "10.0.5.7", "192.168.16.2", "172.18.0.11"}) {
            assertThat(p.matcher(ok).matches()).as(ok).isTrue();
        }
        for (String no : new String[]{"172.18.0.1", "172.17.0.1", "127.0.0.1", "10.0.0.1", "192.168.1.1",
                "8.8.8.8", "172.32.0.2", "172.15.0.2", "0:0:0:0:0:0:0:1", "203.0.113.5"}) {
            assertThat(p.matcher(no).matches()).as(no).isFalse();
        }
    }
}
