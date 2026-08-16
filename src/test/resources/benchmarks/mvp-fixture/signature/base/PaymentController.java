package fixture;

public final class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    public Payment get(String paymentId) {
        return service.process(paymentId);
    }
}
