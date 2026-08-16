package fixture

@Controller
class PaymentController(
    @Qualifier("primary") private val service: PaymentService
) {
    @GetMapping("/payments/{id}")
    fun get(id: String): Payment = service.process(id)
}
