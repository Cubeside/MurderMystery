/*
 * Murder Mystery is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Murder Mystery is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Murder Mystery.  If not, see <http://www.gnu.org/licenses/>.
 */

package pl.plajer.murdermystery.events;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.golde.bukkit.corpsereborn.CorpseAPI.events.CorpseClickEvent;

import pl.plajer.murdermystery.Main;
import pl.plajer.murdermystery.arena.Arena;
import pl.plajer.murdermystery.arena.ArenaManager;
import pl.plajer.murdermystery.arena.ArenaRegistry;
import pl.plajer.murdermystery.arena.ArenaUtils;
import pl.plajer.murdermystery.arena.role.Role;
import pl.plajer.murdermystery.handlers.ChatManager;
import pl.plajer.murdermystery.handlers.items.SpecialItemManager;
import pl.plajer.murdermystery.murdermysteryapi.StatsStorage;
import pl.plajer.murdermystery.user.User;
import pl.plajer.murdermystery.user.UserManager;
import pl.plajer.murdermystery.utils.MessageUtils;
import pl.plajer.murdermystery.utils.Utils;
import pl.plajerlair.core.services.exception.ReportedException;

/**
 * @author Plajer
 * <p>
 * Created at 05.08.2018
 */
public class Events implements Listener {

  private Main plugin;

  public Events(Main plugin) {
    this.plugin = plugin;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  @EventHandler
  public void onCorpseClick(CorpseClickEvent e) {
    if (ArenaRegistry.isInArena(e.getClicker())) {
      e.getClicker().closeInventory();
    }
  }

  @EventHandler
  public void onDrop(PlayerDropItemEvent event) {
    Arena arena = ArenaRegistry.getArena(event.getPlayer());
    if (arena == null) {
      return;
    }
    event.setCancelled(true);
  }

  @EventHandler
  public void onSwordThrow(PlayerInteractEvent e) {
    try {
      Arena arena = ArenaRegistry.getArena(e.getPlayer());
      if (arena == null) {
        return;
      }
      if(!Role.isRole(Role.MURDERER, e.getPlayer())) {
        return;
      }
      final Player attacker = e.getPlayer();
      final User attackerUser = UserManager.getUser(attacker.getUniqueId());
      //todo not hardcoded!
      if(attacker.getInventory().getItemInMainHand().getType() == Material.IRON_SWORD) {
        if(attackerUser.getCooldown("sword_shoot") > 0) {
          return;
        }
        attackerUser.setCooldown("sword_shoot", 5);
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        final ArmorStand stand = (ArmorStand) attacker.getWorld().spawnEntity(attacker.getLocation(), EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setInvulnerable(true);
        stand.setItemInHand(new ItemStack(Material.IRON_SWORD, 1));
        stand.setRightArmPose(new EulerAngle(0, 0, 1));
        stand.setCollidable(false);
        stand.setSilent(true);
        new BukkitRunnable() {
          double t = 0;
          Location loc = attacker.getLocation();
          Vector direction = loc.getDirection().normalize();

          @Override
          public void run() {
            t += 0.5;
            double x = direction.getX() * t;
            double y = direction.getY() * t + 0.5;
            double z = direction.getZ() * t;
            loc.add(x, y, z);
            stand.teleport(loc);
            for (Entity en : loc.getChunk().getEntities()) {
              if (!(en instanceof LivingEntity && en instanceof Player)) {
                continue;
              }
              Player victim = (Player) en;
              if(ArenaRegistry.isInArena(victim) && UserManager.getUser(victim.getUniqueId()).isSpectator()) {
                continue;
              }
              if (victim.getLocation().distance(loc) < 1.0) {
                if (!victim.equals(attacker)) {
                  Utils.spawnCorpse(victim, arena);
                  victim.damage(100.0);
                  victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_DEATH, 50, 1);
                  MessageUtils.sendTitle(victim, ChatManager.colorMessage("In-Game.Messages.Game-End-Messages.Titles.Died"), 5, 40, 5);
                  MessageUtils.sendSubTitle(victim, ChatManager.colorMessage("In-Game.Messages.Game-End-Messages.Subtitles.Murderer-Killed-You"), 5, 40, 5);
                  attackerUser.addStat(StatsStorage.StatisticType.LOCAL_KILLS, 1);
                  ArenaUtils.addScore(attackerUser, ArenaUtils.ScoreAction.KILL_PLAYER);
                  if (Role.isRole(Role.ANY_DETECTIVE, victim)) {
                    if (Role.isRole(Role.FAKE_DETECTIVE, victim)) {
                      arena.setFakeDetective(null);
                    }
                    ArenaUtils.dropBowAndAnnounce(arena, victim);
                  }
                }
              }
            }
            loc.subtract(x, y, z);
            if (t > 20) {
              this.cancel();
              stand.remove();
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> attacker.getInventory().setItem(1, new ItemStack(Material.IRON_SWORD)), 5 * 21);
          }
        }.runTaskTimer(plugin, 0, 1);
        Utils.applyActionBarCooldown(attacker, 5);
      }
    } catch (Exception ex) {
      new ReportedException(plugin, ex);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onCommandExecute(PlayerCommandPreprocessEvent event) {
    try {
      Arena arena = ArenaRegistry.getArena(event.getPlayer());
      if (arena == null) {
        return;
      }
      if (!plugin.getConfig().getBoolean("Block-Commands-In-Game", true)) {
        return;
      }
      for (String msg : plugin.getConfig().getStringList("Whitelisted-Commands")) {
        if (event.getMessage().contains(msg)) {
          return;
        }
      }
      if (event.getMessage().startsWith("/mm") || event.getMessage().contains("leave")
          || event.getMessage().contains("stats") || event.getMessage().startsWith("/mma")) {
        return;

      }
      if (event.getPlayer().isOp()) {
        return;
      }
      event.setCancelled(true);
      event.getPlayer().sendMessage(ChatManager.PLUGIN_PREFIX + ChatManager.colorMessage("In-Game.Only-Command-Ingame-Is-Leave"));
    } catch (Exception ex) {
      new ReportedException(plugin, ex);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onLeave(PlayerInteractEvent event) {
    try {
      if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
        return;
      }
      Arena arena = ArenaRegistry.getArena(event.getPlayer());
      if (arena == null) {
        return;
      }
      ItemStack itemStack = event.getPlayer().getInventory().getItemInMainHand();
      if (itemStack == null || itemStack.getItemMeta() == null || itemStack.getItemMeta().getDisplayName() == null) {
        return;
      }
      String key = SpecialItemManager.getRelatedSpecialItem(itemStack);
      if (key == null) {
        return;
      }
      if (SpecialItemManager.getRelatedSpecialItem(itemStack).equalsIgnoreCase("Leave")) {
        event.setCancelled(true);
        if (plugin.isBungeeActivated()) {
          plugin.getBungeeManager().connectToHub(event.getPlayer());
        } else {
          ArenaManager.leaveAttempt(event.getPlayer(), arena);
        }
      }
    } catch (Exception ex) {
      new ReportedException(plugin, ex);
    }
  }

  @EventHandler
  public void onFoodLevelChange(FoodLevelChangeEvent event) {
    if (event.getEntity().getType() != EntityType.PLAYER) {
      return;
    }
    Arena arena = ArenaRegistry.getArena((Player) event.getEntity());
    if (arena == null) {
      return;
    }
    event.setFoodLevel(20);
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH)
  //highest priority to fully protecc our game (i didn't set it because my test server was destroyed, n-no......)
  public void onBlockBreakEvent(BlockBreakEvent event) {
    if (!ArenaRegistry.isInArena(event.getPlayer())) {
      return;
    }
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH)
  //highest priority to fully protecc our game (i didn't set it because my test server was destroyed, n-no......)
  public void onBuild(BlockPlaceEvent event) {
    if (!ArenaRegistry.isInArena(event.getPlayer())) {
      return;
    }
    event.setCancelled(true);
  }

  @EventHandler
  public void onCraft(PlayerInteractEvent event) {
    if (!ArenaRegistry.isInArena(event.getPlayer())) {
      return;
    }
    if (event.getPlayer().getTargetBlock(null, 7).getType() == Material.WORKBENCH) {
      event.setCancelled(true);
    }
  }

}
