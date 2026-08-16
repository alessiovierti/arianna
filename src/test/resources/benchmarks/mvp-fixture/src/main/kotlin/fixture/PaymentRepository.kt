package fixture

@Repository
class PaymentRepository {
    fun find(id: String): Payment = Payment(id)
}
