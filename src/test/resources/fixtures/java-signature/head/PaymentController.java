package fixture;

public final class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    public Payment get(String paymentId, String currency) {
        return service.process(paymentId, currency);
    }
}
