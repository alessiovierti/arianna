package fixture

interface PaymentService {
    fun process(paymentId: String): Payment
}

data class Payment(val id: String)
