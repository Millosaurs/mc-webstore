package me.millosaurs.webstoreApi;

import org.bukkit.Material;

public class PendingItem {
    public final Material material;
    public final int amount;
    public final String note;

    public PendingItem(Material material, int amount, String note) {
        this.material = material;
        this.amount = amount;
        this.note = note;
    }

    public PendingItem(Material material, int amount) {
        this(material, amount, null);
    }

    @Override
    public String toString() {
        return amount + "x " + material.name() + (note != null ? " (" + note + ")" : "");
    }
}