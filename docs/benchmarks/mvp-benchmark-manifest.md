# Arianna MVP benchmark manifest

The fixture at `src/test/resources/benchmarks/mvp-fixture` is small and deterministic. Expected answers are source-verifiable references, not generated outputs.

| ID | Task | Reference answer |
|---|---|---|
| B01 | Where is the payment flow handled? | `fixture.PaymentService.process`, consumed by `PaymentController`. |
| B02 | Which Spring components are involved? | `PaymentController`, `PaymentRepository`, `PaymentEventHandler`, `PaymentService` injection and `primary` qualifier. |
| B03 | Which endpoint is involved? | `GET /payments/{id}` in `PaymentController.get`. |
| B04 | Which tests/mocks may need updates? | `PaymentServiceTest.processPayment` and its `PaymentService` mock. |
| B05 | Which documents/configuration matter? | `README.md`, `application.yml`, `application.properties`. |
| B06 | What is the signature-change impact? | The base/head Java fixture changes `process(String)` to `process(String, String)` and updates `PaymentController.get`. |
| B07 | Which relationships cannot be resolved statically? | `DynamicWiring.dynamicBeanName` and the dynamic mock remain `possible`/`unresolved`. |

Run the automated measurements with:

```bash
learn benchmark <fixture> --baseline --json
learn benchmark <fixture> --compare --json
```

Automated output is evidence for engineering quality, not proof of productivity. Human observations use [validation-session-template.md](validation-session-template.md).
