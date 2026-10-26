package com.leadsystems.minecraft.ascension.market;

import org.bukkit.Material;

import java.util.List;

/** One source of truth for exchange previews and the durable inventory transaction. */
public record CurrencyExchange(String id, Material input, int inputAmount, Material output, int outputAmount) {
    public static final List<CurrencyExchange> ALL = List.of(
            new CurrencyExchange("emerald_to_diamond", Material.EMERALD_BLOCK, 4, Material.DIAMOND_BLOCK, 1),
            new CurrencyExchange("diamond_to_emerald", Material.DIAMOND_BLOCK, 1, Material.EMERALD_BLOCK, 4),
            new CurrencyExchange("diamond_to_netherite", Material.DIAMOND_BLOCK, 4, Material.NETHERITE_INGOT, 1),
            new CurrencyExchange("netherite_to_diamond", Material.NETHERITE_INGOT, 1, Material.DIAMOND_BLOCK, 4),
            new CurrencyExchange("emerald_to_netherite", Material.EMERALD_BLOCK, 16, Material.NETHERITE_INGOT, 1),
            new CurrencyExchange("netherite_to_emerald", Material.NETHERITE_INGOT, 1, Material.EMERALD_BLOCK, 16));

    public static CurrencyExchange find(String id) {
        return ALL.stream().filter(exchange -> exchange.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown currency exchange"));
    }

    public String give() { return amount(input, inputAmount); }
    public String receive() { return amount(output, outputAmount); }

    private static String amount(Material material, int count) {
        String name = switch (material) {
            case EMERALD_BLOCK -> "Emerald Block";
            case DIAMOND_BLOCK -> "Diamond Block";
            case NETHERITE_INGOT -> "Netherite Ingot";
            default -> throw new IllegalArgumentException("Unsupported currency");
        };
        return count + " " + name + (count == 1 ? "" : "s");
    }
}
