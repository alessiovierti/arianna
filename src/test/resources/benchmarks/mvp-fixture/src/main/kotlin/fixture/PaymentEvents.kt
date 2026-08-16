package fixture

data class PaymentCreated(val id: String)

@Component
class PaymentEventHandler {
    @EventListener
    fun on(event: PaymentCreated) = Unit
}
