package com.banking.transactionsservice.contract;

import com.banking.transactionsservice.contract.model.MovementMoney;
import org.springframework.boot.jackson.JacksonMixin;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JacksonMixin(MovementMoney.class)
abstract class MovementMoneyJson {
    @JsonDeserialize(using = ExactAmount.class)
    abstract void setAmount(String value);

    static class ExactAmount extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "Movement amount must be a decimal string");
            }
            return parser.getString();
        }
    }
}
