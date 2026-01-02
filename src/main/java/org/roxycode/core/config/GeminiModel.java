package org.roxycode.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;

@Introspected
public class GeminiModel {

    @JsonProperty("api_name") // Maps TOML api_name to this field
    private String apiName;

    @JsonProperty("input_price_per_1m")
    private double inputPrice;

    @JsonProperty("output_price_per_1m")
    private double outputPrice;

    private String description;

    // Getters and Setters
    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public double getInputPrice() {
        return inputPrice;
    }

    public void setInputPrice(double inputPrice) {
        this.inputPrice = inputPrice;
    }

    public double getOutputPrice() {
        return outputPrice;
    }

    public void setOutputPrice(double outputPrice) {
        this.outputPrice = outputPrice;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return String.format("Model: %s (Input: $%.2f, Output: $%.2f)", apiName, inputPrice, outputPrice);
    }
}