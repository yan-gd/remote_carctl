package org.example.domain.customer.gateway;

import org.example.domain.customer.Customer;

public interface CustomerGateway {
    Customer getByById(String customerId);
}
