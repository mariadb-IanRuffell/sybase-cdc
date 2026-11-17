package poc.api.config;

import org.apache.ignite.client.IgniteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GridGainConfig {
    /** Thin client over all cluster nodes (it fails over if one is down); used for the compute jobs and the node list. */
    @Bean(destroyMethod = "close")
    IgniteClient igniteClient(@Value("${gridgain.addresses}") String addresses) {
        return IgniteClient.builder().addresses(addresses.split(",")).build();
    }
}
