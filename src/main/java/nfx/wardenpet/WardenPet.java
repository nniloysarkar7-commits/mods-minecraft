package nfx.wardenpet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WardenPet extends JavaPlugin implements Listener {

    private static final double PET_HEALTH = 3000.0;
    private static final double DAMAGE_MULT = 1.5;          // +50% damage
    private static final long COMBAT_MS = 15000L;           // owner's target stays valid 15s
    private static final long DARK_MS = 5000L;              // victim darkness 5s
    private static final double TELEPORT_DIST_SQ = 24.0 * 24.0;
    private static final double FOLLOW_DIST_SQ = 5.0 * 5.0;

    private NamespacedKey ownerKey;
    // ownerUUID -> (targetUUID -> expiry millis)
    private final Map<UUID, Map<UUID, Long>> combat = new HashMap<>();
    // playerUUID -> darkness (from owner's hit) expiry millis
    private final Map<UUID, Long> darkUntil = new HashMap<>();

    @Override
    public void onEnable() {
        ownerKey = new NamespacedKey(this, "owner");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 5L);
    }

    // ---------- helpers ----------

    private UUID ownerOf(Entity e) {
        if (!(e instanceof Warden)) return null;
        String s = e.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isPet(Entity e) {
        return ownerOf(e) != null;
    }

    private LivingEntity realDamager(Entity d) {
        if (d instanceof Projectile pr && pr.getShooter() instanceof LivingEntity shooter) return shooter;
        if (d instanceof LivingEntity le) return le;
        return null;
    }

    private List<Warden> petsNear(Player p, double r) {
        List<Warden> list = new ArrayList<>();
        for (Warden w : p.getWorld().getEntitiesByClass(Warden.class)) {
            if (p.getUniqueId().equals(ownerOf(w))
                    && w.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                list.add(w);
            }
        }
        return list;
    }

    private boolean allowed(Warden w, LivingEntity t) {
        UUID ownerId = ownerOf(w);
        if (ownerId == null) return true;
        if (t.getUniqueId().equals(ownerId) || isPet(t)) return false;
        Map<UUID, Long> m = combat.get(ownerId);
        if (m == null) return false;
        Long exp = m.get(t.getUniqueId());
        return exp != null && exp > System.currentTimeMillis();
    }

    private void markTarget(UUID ownerId, LivingEntity victim) {
        if (victim.getUniqueId().equals(ownerId) || isPet(victim)) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return;
        combat.computeIfAbsent(ownerId, k -> new HashMap<>())
                .put(victim.getUniqueId(), System.currentTimeMillis() + COMBAT_MS);
        for (Warden w : owner.getWorld().getEntitiesByClass(Warden.class)) {
            if (!ownerId.equals(ownerOf(w)) || !w.hasAI()) continue;
            if (!w.getWorld().equals(victim.getWorld())) continue;
            if (w.getLocation().distanceSquared(victim.getLocation()) > 48.0 * 48.0) continue;
            w.increaseAnger(victim, 150);
            w.setTarget(victim);
        }
    }

    private void tame(Warden w, Player p) {
        w.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
        w.setPersistent(true);
        w.setRemoveWhenFarAway(false);
        w.setTarget(null);
        w.clearAnger(p);

        AttributeInstance hp = w.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            for (double v : new double[]{PET_HEALTH, 2048.0, 1024.0}) {
                try {
                    hp.setBaseValue(v);
                    break;
                } catch (IllegalArgumentException ignored) {
                    // value above server limit, try lower
                }
            }
            w.setHealth(Math.min(hp.getValue(), PET_HEALTH));
        }

        w.customName(Component.text(p.getName() + "'s Warden", NamedTextColor.AQUA));
        w.setCustomNameVisible(true);
        w.getWorld().spawnParticle(Particle.HEART, w.getLocation().add(0, 2.5, 0), 20, 1, 1, 1);
        p.sendMessage(Component.text("Warden tamed! (HP 3000, +50% damage)", NamedTextColor.GREEN));
    }

    // ---------- main loop ----------

    private void tick() {
        long now = System.currentTimeMillis();
        combat.values().forEach(m -> m.values().removeIf(t -> t < now));
        combat.values().removeIf(Map::isEmpty);
        darkUntil.values().removeIf(t -> t < now);

        List<Warden> pets = new ArrayList<>();
        List<Warden> wild = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Warden w : world.getEntitiesByClass(Warden.class)) {
                if (isPet(w)) pets.add(w);
                else wild.add(w);
            }
        }
        if (pets.isEmpty()) return;

        for (Warden w : pets) managePet(w);
        cleanDarkness(pets, wild);
    }

    private void managePet(Warden w) {
        UUID ownerId = ownerOf(w);
        if (ownerId == null) return;

        // only the owner's targets allowed
        LivingEntity t = w.getTarget();
        if (t != null && !allowed(w, t)) w.setTarget(null);
        for (Entity n : w.getNearbyEntities(24, 12, 24)) {
            if (n instanceof LivingEntity le && !allowed(w, le) && w.getAnger(le) > 0) {
                w.clearAnger(le);
            }
        }

        Player p = Bukkit.getPlayer(ownerId);
        if (p == null || !p.isOnline() || !w.hasAI()) return;

        Location pl = p.getLocation();
        if (!p.getWorld().equals(w.getWorld())
                || w.getLocation().distanceSquared(pl) > TELEPORT_DIST_SQ) {
            w.teleport(pl);
            return;
        }
        if (w.getTarget() == null && w.getLocation().distanceSquared(pl) > FOLLOW_DIST_SQ) {
            w.getPathfinder().moveTo(pl, 1.2);
        }
    }

    // pet Warden nearby: no automatic darkness (only owner's victims get it)
    private void cleanDarkness(List<Warden> pets, List<Warden> wild) {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPotionEffect(PotionEffectType.DARKNESS)) continue;
            Long until = darkUntil.get(p.getUniqueId());
            if (until != null && until > now) continue;
            if (isNear(p, pets, 32) && !isNear(p, wild, 32)) {
                p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
    }

    private boolean isNear(Player p, List<Warden> list, double r) {
        for (Warden w : list) {
            if (w.getWorld().equals(p.getWorld())
                    && w.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                return true;
            }
        }
        return false;
    }

    // ---------- events ----------

    // right click: tame (echo shard) / sit-stand toggle (owner)
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Warden w)) return;

        Player p = e.getPlayer();
        UUID owner = ownerOf(w);

        if (owner == null) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() != Material.ECHO_SHARD) return;
            e.setCancelled(true);
            if (p.getGameMode() != GameMode.CREATIVE) {
                if (hand.getAmount() <= 1) {
                    p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                } else {
                    hand.setAmount(hand.getAmount() - 1);
                    p.getInventory().setItemInMainHand(hand);
                }
            }
            tame(w, p);
            return;
        }

        if (owner.equals(p.getUniqueId())) {
            e.setCancelled(true);
            boolean wasFollowing = w.hasAI();
            if (wasFollowing) w.setTarget(null);
            w.setAI(!wasFollowing);
            p.sendActionBar(Component.text(
                    wasFollowing ? "Warden is sitting" : "Warden is following you",
                    NamedTextColor.YELLOW));
        }
    }

    // pet attacks only owner's targets
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getEntity() instanceof Warden w) || !isPet(w)) return;
        LivingEntity t = e.getTarget();
        if (t == null) return;
        if (!allowed(w, t)) e.setCancelled(true);
    }

    // damage rules: 3000 effective HP, +50% damage, no friendly fire
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        LivingEntity attacker = null;
        if (e instanceof EntityDamageByEntityEvent be) attacker = realDamager(be.getDamager());

        if (victim instanceof Warden vw && isPet(vw)) {
            UUID ownerId = ownerOf(vw);
            if (attacker != null && attacker.getUniqueId().equals(ownerId)) {
                e.setCancelled(true);
                return;
            }
            AttributeInstance hp = vw.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null && hp.getValue() < PET_HEALTH) {
                e.setDamage(e.getDamage() * hp.getValue() / PET_HEALTH);
            }
        }

        if (attacker instanceof Warden aw && isPet(aw)) {
            if (victim.getUniqueId().equals(ownerOf(aw)) || isPet(victim)) {
                e.setCancelled(true);
                return;
            }
            e.setDamage(e.getDamage() * DAMAGE_MULT);
        }
    }

    // after damage really happened: owner's target gets marked, victim screen goes dark
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDone(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        LivingEntity attacker = realDamager(e.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;

        // someone hit a pet -> pet fights back
        UUID victimOwner = ownerOf(victim);
        if (victimOwner != null) {
            if (!isPet(attacker)) markTarget(victimOwner, attacker);
            return;
        }

        // someone hit the owner -> pets defend
        if (victim instanceof Player hurt && !isPet(attacker)) {
            markTarget(hurt.getUniqueId(), attacker);
        }

        // owner hit someone -> pets attack it, victim screen goes dark
        if (attacker instanceof Player ap) {
            markTarget(ap.getUniqueId(), victim);
            if (victim instanceof Player vp && !petsNear(ap, 48).isEmpty()) {
                darkUntil.put(vp.getUniqueId(), System.currentTimeMillis() + DARK_MS);
                vp.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0, false, false, false));
            }
        }
    }
}
