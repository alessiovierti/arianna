package fixture

class PaymentServiceTest {
    private val service: PaymentService = mock()

    fun processPayment() = service.process("p-1")

    private fun mock(): PaymentService = TODO("dynamic test mock fixture")
}
