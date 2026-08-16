package fixture;

public interface PaymentService {
    Payment process(String paymentId, String currency);
}
