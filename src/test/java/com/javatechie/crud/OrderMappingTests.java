package com.javatechie.crud;

import com.javatechie.crud.entity.Order;
import com.javatechie.crud.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OrderMappingTests {
    @Autowired
    private OrderRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void jsonFieldsSurviveDatabaseRoundTrip() {
        JsonMapper mapper = JsonMapper.builder().build();
        Order order = mapper.readValue("{\"name\":\"Watch\",\"qty\":2,\"price\":1499}", Order.class);
        assertThat(order.getName()).isEqualTo("Watch");
        assertThat(order.getQty()).isEqualTo(2);
        Order saved = repository.saveAndFlush(order);
        assertThat(saved.getId()).isPositive();
        int savedId = saved.getId();
        entityManager.clear();
        Order reloaded = repository.findById(savedId).orElseThrow();
        assertThat(reloaded.getQty()).isEqualTo(2);
        assertThat(reloaded.getPrice()).isEqualTo(1499);
        assertThat(mapper.readTree(mapper.writeValueAsString(reloaded)).get("name").asString())
                .isEqualTo("Watch");
    }
}
