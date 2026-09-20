package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcContainerEndpoint
import io.samcnpc.core.api.NpcContainerTransferRequest
import io.samcnpc.core.api.NpcContainerTransferResult
import io.samcnpc.core.api.NpcContainerTransferState
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionCompletion
import io.samcnpc.core.api.NpcControlState
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcAttackTiming
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcBlockHit
import io.samcnpc.core.api.NpcEntityHit
import io.samcnpc.core.api.NpcBlockFace
import io.samcnpc.core.api.NpcBlockPlacement
import io.samcnpc.core.api.NpcBlockContainerSlot
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcFishingCast
import io.samcnpc.core.api.NpcFishingState
import io.samcnpc.core.api.NpcFishingReelResult
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcEquipmentDestination
import io.samcnpc.core.api.NpcEquipmentSnapshot
import io.samcnpc.core.api.NpcEquipmentKnowledge
import io.samcnpc.core.api.NpcInventoryLoadSnapshot
import io.samcnpc.core.api.NpcInventoryEntry
import io.samcnpc.core.api.NpcItemStackSnapshot
import io.samcnpc.core.api.NpcItemClassifier
import io.samcnpc.core.api.NpcItemUseState
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.NpcLookRotation
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcVector
import io.samcnpc.core.api.NpcRemovedEvent
import io.samcnpc.core.api.NpcServerTickEvent
import io.samcnpc.core.api.NpcActionCompletedEvent
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.api.NpcLifecycleSnapshot
import io.samcnpc.core.api.NpcLifecycleState
import io.samcnpc.core.api.NpcDismissMode
import io.samcnpc.core.api.NpcWorldView
import io.samcnpc.core.api.PlayerSkinModel
import io.samcnpc.core.api.SkinBinding
import io.samcnpc.core.api.SummonerBinding
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcToolDurability
import io.samcnpc.core.health.NpcHeartSettings
import io.samcnpc.core.activity.NpcActivityEvents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Container
import net.minecraft.core.NonNullList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.projectile.ThrownPotion
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.ThrowablePotionItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.level.ClipContext
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.registries.ForgeRegistries
import net.minecraft.world.item.enchantment.EnchantmentHelper
import java.util.UUID
import kotlin.math.abs

/**
 * A dedicated living entity, deliberately not a ServerPlayer surrogate. It stores only stable
 * identifiers for outside entities; callers provide every mechanical action explicitly.
 */
class SamcnpcEntity(type: EntityType<out SamcnpcEntity>, level: Level) : Mob(type, level), NpcFacade {
    private var summonerBinding: SummonerBinding? = null
    private var skinBinding: SkinBinding? = null
    private val inventory: NonNullList<ItemStack> = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY)
    private var ammunition: ItemStack = ItemStack.EMPTY
    private var totem: ItemStack = ItemStack.EMPTY
    private var selectedHotbarSlot: Int = 0
    private var loadedInventory: NpcInventoryLoadSnapshot? = null
    private var controlInput: NpcControlInput = NpcControlInput.IDLE
    private var controlActionId: UUID? = null
    private var controlExpiresAt: Long = Long.MIN_VALUE
    private var dismissalRequested: Boolean = false
    private var recentDamageEventId: UUID? = null
    private var recentAttackerUuid: UUID? = null
    private var recentHurtGameTime: Long = Long.MIN_VALUE
    private var lastAttackGameTime: Long = Long.MIN_VALUE
    private var appliedMainHandStack: ItemStack = ItemStack.EMPTY
    private var removalAnnounced: Boolean = false
    private var deathEquipmentDropped: Boolean = false
    internal var lifeId: UUID = UUID.randomUUID()
        private set
    internal var summonPoint: io.samcnpc.core.health.NpcSummonPoint? = null
        private set
    private val inventoryActions = NpcInventoryActions(this)
    private val blockBreakController = NpcBlockBreakController(this, ::completeAction)
    private val fishingController = NpcFishingController(this, ::completeAction)
    private val rangedController = NpcRangedAttackController(this, ::completeAction)
    private val navigationController = NpcNavigationController(this, ::completeAction)
    private val itemUseController = NpcItemUseController(this, ::completeAction)
    private var recentActionCompletions: List<NpcActionCompletion> = emptyList()

    init {
        // Persistence and passive contact pickup are body mechanics, not autonomous policy.
        // Keep Mob loot pickup disabled because it also auto-equips; Core owns a bounded inventory-only vacuum.
        setPersistenceRequired()
        setCanPickUpLoot(false)
    }

    override val npcUuid: UUID
        get() = uuid

    override fun registerGoals() {
        // Core intentionally installs no autonomous goals. Behavior invokes explicit primitives.
    }

    override fun createNavigation(level: Level): PathNavigation = NpcGroundNavigation(this, level)

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_SUMMONER_UUID, java.util.Optional.empty())
        entityData.define(DATA_SUMMONER_NAME, "")
        entityData.define(DATA_SKIN_VALUE, "")
        entityData.define(DATA_SKIN_SIGNATURE, "")
        entityData.define(DATA_SKIN_MODEL, PlayerSkinModel.CLASSIC.ordinal.toByte())
        entityData.define(DATA_SKIN_REVISION, "")
        entityData.define(DATA_SELECTED_SLOT, 0.toByte())
        entityData.define(DATA_SWING_SEQUENCE, 0)
        entityData.define(DATA_ANIMATIONS_ENABLED, true)
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == DATA_SWING_SEQUENCE && level().isClientSide) {
            // The vanilla animate packet remains the normal path. This tracked sequence covers a
            // client that begins tracking after that transient packet, while LivingEntity still
            // owns the actual arm timing.
            swing(InteractionHand.MAIN_HAND)
        }
    }

    override fun tick() {
        if (!level().isClientSide) {
            if (isAlive) {
                if (NpcSettingsConfig.enabled(NpcSetting.IMMORTAL)) health = maxHealth
                itemUseController.beforeTick()
                navigationController.beforeTick()
                expireControlIfNeeded()
                applyControlInput()
            } else {
                cancelActiveActionsForRemoval(NpcLifecycleState.DEAD)
            }
        }
        super.tick()
        if (!level().isClientSide) {
            // Vanilla keeps a dead body ticking through its death animation. It must finish that
            // lifecycle without exposing world actions or decision ticks to Behavior.
            if (!isAlive || isRemoved) {
                cancelActiveActionsForRemoval(if (!isAlive) NpcLifecycleState.DEAD else NpcLifecycleState.REMOVED)
                return
            }
            navigationController.tick()
            inventoryActions.tickPassivePickup()
            fishingController.tick()
            rangedController.tick()
            itemUseController.afterTick()
            blockBreakController.tick()
            val snapshot = snapshot()
            val server = (level() as? ServerLevel)?.server
            if (server != null) {
                NpcActivityEvents.existing(server)?.updatePosition(this)
                val handle = NpcHandle(uuid, name.string)
                val runtime = CoreNpcApi.service(server).runtime(handle)
                if (runtime != null) {
                    MinecraftForge.EVENT_BUS.post(NpcServerTickEvent(handle, snapshot, runtime, runtime.worldView()))
                }
            }
        }
    }

    override fun aiStep() {
        // Mob does not advance LivingEntity's swing clock (Monster does so explicitly).
        // Run on both sides: swing packets start the clock; the renderer reads attackAnim.
        updateSwingTime()
        super.aiStep()
    }

    override fun remove(reason: RemovalReason) {
        val state = removalState(reason)
        if (!level().isClientSide && !removalAnnounced) {
            cancelActiveActionsForRemoval(state)
        }
        super.remove(reason)
        if (!level().isClientSide && !removalAnnounced) {
            removalAnnounced = true
            val server = (level() as? ServerLevel)?.server
            val lifecycle = NpcLifecycleSnapshot(
                handle = NpcHandle(uuid, name.string),
                state = state,
                dimensionId = level().dimension().location().toString(),
                gameTime = level().gameTime,
            )
            if (server != null) {
                NpcActivityEvents.existing(server)?.removed(this, reason)
                CoreNpcApi.unregister(this, server, state)
                if (reason.shouldDestroy() && io.samcnpc.core.health.NpcRespawns.data(server).find(uuid) == null) {
                    io.samcnpc.core.health.NpcRespawns.data(server).removeSpawnPoint(uuid)
                }
            }
            MinecraftForge.EVENT_BUS.post(NpcRemovedEvent(lifecycle))
        }
    }

    override fun onRemovedFromWorld() {
        super.onRemovedFromWorld()
        val server = (level() as? ServerLevel)?.server ?: return
        // Forge invokes this for tracking/unload paths that bypass Entity.remove().
        fishingController.cancel("fishing body left the loaded world", removal = true)
        NpcActivityEvents.existing(server)?.leftWorld(this)
    }

    override fun die(source: net.minecraft.world.damagesource.DamageSource) {
        // Vanilla has already offered both held totems. Reserve protection precedes accepted death,
        // so it cannot drop inventory, cancel work as DEAD, or enqueue a respawn.
        if (!dead && !isRemoved && io.samcnpc.core.health.NpcTotemReserve.protect(this, source)) return
        super.die(source)
        // Forge's death veto runs before LivingEntity sets dead. A totem never enters this path.
        // Some killer hooks bypass the loot path, but accepted death still needs one lifecycle decision.
        if (dead && !level().isClientSide && !deathEquipmentDropped) dropEquipment()
    }

    override fun dropEquipment() {
        if (level().isClientSide || deathEquipmentDropped) return
        deathEquipmentDropped = true
        if (dead && !dismissalRequested) {
            val policy = NpcSettingsConfig.deathPolicy()
            val retained = policy.items == io.samcnpc.core.health.NpcDeathPolicy.Items.KEEP
            val scheduled = policy.respawn && io.samcnpc.core.health.NpcRespawns.schedule(this, retained)
            if ((retained && scheduled) || policy.items == io.samcnpc.core.health.NpcDeathPolicy.Items.DISCARD) {
                clearAuthoritativeItems()
                return
            }
        }
        // Main hand aliases the selected inventory stack; drop each real store exactly once.
        for (slot in inventory.indices) {
            dropAndClearInventorySlot(slot)
        }
        dropAndClearEquipmentSlot(EquipmentSlot.OFFHAND)
        dropAndClearEquipmentSlot(EquipmentSlot.HEAD)
        dropAndClearEquipmentSlot(EquipmentSlot.CHEST)
        dropAndClearEquipmentSlot(EquipmentSlot.LEGS)
        dropAndClearEquipmentSlot(EquipmentSlot.FEET)
        dropAndClearReserve(isAmmunition = true)
        dropAndClearReserve(isAmmunition = false)
        refreshMainHandAttributes()
    }

    private fun clearAuthoritativeItems() {
        for (slot in inventory.indices) inventory[slot] = ItemStack.EMPTY
        for (slot in EquipmentSlot.values()) super.setItemSlot(slot, ItemStack.EMPTY)
        ammunition = ItemStack.EMPTY
        totem = ItemStack.EMPTY
        refreshMainHandAttributes()
    }

    internal fun respawnSnapshot(keep: Boolean, nextLife: UUID): CompoundTag {
        val saved = saveWithoutId(CompoundTag())
        val result = CompoundTag()
        for (key in io.samcnpc.core.health.NpcRespawnData.BODY_KEYS) saved.get(key)?.let { result.put(key, it.copy()) }
        result.putUUID("samcnpcLife", nextLife)
        if (!keep) for (key in listOf("Items", "ArmorItems", "HandItems", "ammunition", "totem")) result.remove(key)
        return result
    }

    override fun hurtCurrentlyUsedShield(amount: Float) {
        val shield = useItem
        if (level().isClientSide || amount < 3.0F ||
            !shield.canPerformAction(net.minecraftforge.common.ToolActions.SHIELD_BLOCK)) return
        val hand = usedItemHand
        // LivingEntity leaves this hook empty; Player alone pays this vanilla durability cost.
        // Forge ShieldBlockEvent decides whether to call it, so its veto remains authoritative.
        NpcToolDurability.perform(shield) {
            shield.hurtAndBreak(1 + net.minecraft.util.Mth.floor(amount), this) { it.broadcastBreakEvent(hand) }
        }
        if (!shield.isEmpty) return
        setItemInHand(hand, ItemStack.EMPTY)
        stopUsingItem()
        itemUseController.finish(NpcActionResult.failed("shield broke while blocking", NpcActionCode.MISSING_RESOURCE))
        playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + random.nextFloat() * 0.4F)
    }

    override fun hurt(source: net.minecraft.world.damagesource.DamageSource, amount: Float): Boolean {
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null && NpcSettingsConfig.enabled(NpcSetting.IMMORTAL, !NpcHeartSettings.enabled(serverLevel.server))) {
            return false
        }
        val accepted = super.hurt(source, amount)
        if (accepted && !level().isClientSide) {
            val attacker = source.entity
            if (attacker != null && attacker.uuid != uuid) {
                recentDamageEventId = UUID.randomUUID()
                recentAttackerUuid = attacker.uuid
                recentHurtGameTime = level().gameTime
            }
        }
        return accepted
    }

    internal fun selectedInventorySlot(): Int = selectedHotbarSlot

    override fun getMainHandItem(): ItemStack = NpcHotbarAlias.stack(inventory, selectedHotbarSlot)

    override fun getOffhandItem(): ItemStack = super.getOffhandItem()

    override fun getItemBySlot(slot: EquipmentSlot): ItemStack = when (slot) {
        EquipmentSlot.MAINHAND -> mainHandItem
        else -> super.getItemBySlot(slot)
    }

    override fun setItemSlot(slot: EquipmentSlot, stack: ItemStack) {
        if (slot == EquipmentSlot.MAINHAND) {
            replaceInventoryStack(selectedHotbarSlot, stack)
            return
        }
        super.setItemSlot(slot, stack)
    }

    fun bindSummoner(player: ServerPlayer) {
        if (summonPoint == null) summonPoint = io.samcnpc.core.health.NpcSummonPoint.capture(this)
        summonerBinding = SummonerBinding(player.uuid, player.gameProfile.name.take(MAX_NAME_LENGTH))
        refreshSkin(player)
        syncBinding()
    }

    fun refreshSkin(player: ServerPlayer): NpcActionResult {
        val binding = summonerBinding
        if (binding == null || binding.summonerUuid != player.uuid) {
            return NpcActionResult.rejected("player is not this NPC's summoner")
        }
        val captured = SkinBinding.capture(player)
        val persisted = skinBinding
        // PlayerLoggedInEvent can run while Mojang profile properties are not yet exposed to the
        // integrated/dedicated server. Treat an empty capture as unavailable data, never as a
        // request to erase a valid persisted snapshot and fall back to a random-looking default.
        if (!captured.hasTextureSnapshot && persisted?.hasTextureSnapshot == true) {
            return NpcActionResult.succeeded("skin profile is not available yet; retained the persisted skin snapshot")
        }
        skinBinding = captured
        syncSkin()
        return NpcActionResult.succeeded("skin snapshot refreshed")
    }

    internal fun setRespawnPoint(point: io.samcnpc.core.health.NpcSummonPoint) {
        check(!level().isClientSide) { "Respawn point changes require the server" }
        summonPoint = point
    }

    fun summonerBinding(): SummonerBinding? = summonerBinding

    fun skinBinding(): SkinBinding? = skinBinding

    fun isControlledBy(player: ServerPlayer): Boolean = player.hasPermissions(2) || summonerBinding?.summonerUuid == player.uuid

    /** Dismissal is a service-owned lifecycle action; it can never silently destroy a Core store. */
    internal fun dismiss(mode: NpcDismissMode): NpcActionResult {
        if (isRemoved) {
            return NpcActionResult.rejected("NPC is already removed", NpcActionCode.NOT_FOUND)
        }
        if (mode == NpcDismissMode.ONLY_IF_EMPTY && hasAuthoritativeItems()) {
            return NpcActionResult.rejected("NPC still has inventory, equipment, or reserve items", NpcActionCode.CONFLICT)
        }
        if (mode == NpcDismissMode.DROP_INVENTORY) {
            dropEquipment()
        }
        dismissalRequested = true
        discard()
        return NpcActionResult.succeeded("NPC dismissed", channel = NpcActionChannel.INVENTORY)
    }

    override fun inspectBody(): io.samcnpc.core.api.NpcBodyInspection = NpcBodyInspections.capture(this)

    override fun snapshot(): NpcSnapshot {
        val hurtAge = if (recentHurtGameTime == Long.MIN_VALUE) null else (level().gameTime - recentHurtGameTime).coerceAtLeast(0)
        val fraction = if (maxHealth <= 0.0F) 0.0 else (health.toDouble() / maxHealth.toDouble()).coerceIn(0.0, 1.0)
        return NpcSnapshot(
            npcUuid = uuid,
            summonerUuid = summonerBinding?.summonerUuid,
            dimensionId = level().dimension().location().toString(),
            position = NpcPosition(x, y, z),
            eyePosition = NpcPosition(eyePosition.x, eyePosition.y, eyePosition.z),
            velocity = NpcVector(deltaMovement.x, deltaMovement.y, deltaMovement.z),
            yaw = yRot,
            pitch = xRot,
            onGround = onGround(),
            inWater = isInWater,
            inLava = isInLava,
            climbing = onClimbable(),
            riding = isPassenger,
            sprinting = isSprinting,
            sneaking = isShiftKeyDown,
            lastDamageEventId = recentDamageEventId,
            lastDamageSourceEntityUuid = recentAttackerUuid,
            lastDamageAgeTicks = hurtAge,
            healthFraction = fraction,
            gameTime = level().gameTime,
            attackStrength = attackStrengthScale(),
            itemUse = itemUseState(),
            blockBreak = blockBreakController.snapshot(),
            rangedAttack = rangedController.snapshot(),
            fishing = fishingController.state(),
            equipment = equipmentKnowledge(),
            selectedHotbarSlot = selectedHotbarSlot,
            ignoreMissingMiningTool = NpcSettingsConfig.enabled(NpcSetting.IGNORE_MISSING_TOOL),
            bareHandsMiningOnly = NpcSettingsConfig.enabled(NpcSetting.BARE_HANDS_ONLY),
            control = controlActionId?.let { NpcControlState(it, controlInput, controlExpiresAt) },
            navigation = navigationController.snapshot(),
            recentCompletions = recentActionCompletions,
        )
    }

    override fun inventoryLoadSnapshot(): NpcInventoryLoadSnapshot? = loadedInventory

    override fun inventoryContents(): List<NpcInventoryEntry> =
        inventory.indices.map { slot ->
            val stack = inventory[slot]
            NpcInventoryEntry(slot, itemSnapshot(stack), NpcItemClassifier.profile(stack))
        }

    /** Used only by the short-lived server-side vanilla menu container. */
    fun menuInventoryStack(slot: Int): ItemStack = inventory[slot]

    /** Used only by the short-lived server-side vanilla menu container. */
    fun setMenuInventoryStack(slot: Int, stack: ItemStack) {
        replaceInventoryStack(slot, stack)
    }

    /** Used only by the short-lived server-side vanilla menu container. */
    fun removeMenuInventoryStack(slot: Int, amount: Int): ItemStack {
        if (slot !in inventory.indices || amount <= 0) {
            return ItemStack.EMPTY
        }
        val current = inventory[slot]
        val removed = current.split(amount)
        replaceInventoryStack(slot, current)
        return removed
    }

    /** Used only by the main-hand GUI alias slot; it never owns a second inventory stack. */
    fun menuSelectedHotbarStack(): ItemStack = NpcHotbarAlias.stack(inventory, selectedHotbarSlot)

    /** Used only by the main-hand GUI alias slot; it writes directly to the selected hotbar index. */
    fun setMenuSelectedHotbarStack(stack: ItemStack) {
        replaceInventoryStack(selectedHotbarSlot, stack)
    }

    /** Used only by the main-hand GUI alias slot. */
    fun removeMenuSelectedHotbarStack(amount: Int): ItemStack {
        if (amount <= 0) {
            return ItemStack.EMPTY
        }
        val selected = NpcHotbarAlias.stack(inventory, selectedHotbarSlot)
        val removed = selected.split(amount)
        replaceInventoryStack(selectedHotbarSlot, selected)
        return removed
    }

    /** Slot mapping: head, chest, legs, feet, main hand, off hand, ammunition, totem. */
    fun menuEquipmentStack(slot: Int): ItemStack = when (slot) {
        EQUIPMENT_HEAD -> super.getItemBySlot(EquipmentSlot.HEAD)
        EQUIPMENT_CHEST -> super.getItemBySlot(EquipmentSlot.CHEST)
        EQUIPMENT_LEGS -> super.getItemBySlot(EquipmentSlot.LEGS)
        EQUIPMENT_FEET -> super.getItemBySlot(EquipmentSlot.FEET)
        EQUIPMENT_MAIN_HAND -> mainHandItem
        EQUIPMENT_OFF_HAND -> offhandItem
        EQUIPMENT_AMMUNITION -> ammunition
        EQUIPMENT_TOTEM -> totem
        else -> ItemStack.EMPTY
    }

    /** Used only by the short-lived server-side equipment menu container. */
    fun setMenuEquipmentStack(slot: Int, stack: ItemStack) {
        when (slot) {
            EQUIPMENT_HEAD -> super.setItemSlot(EquipmentSlot.HEAD, stack)
            EQUIPMENT_CHEST -> super.setItemSlot(EquipmentSlot.CHEST, stack)
            EQUIPMENT_LEGS -> super.setItemSlot(EquipmentSlot.LEGS, stack)
            EQUIPMENT_FEET -> super.setItemSlot(EquipmentSlot.FEET, stack)
            EQUIPMENT_MAIN_HAND -> setItemSlot(EquipmentSlot.MAINHAND, stack)
            EQUIPMENT_OFF_HAND -> super.setItemSlot(EquipmentSlot.OFFHAND, stack)
            EQUIPMENT_AMMUNITION -> ammunition = stack.copy()
            EQUIPMENT_TOTEM -> totem = stack.copyWithCount(stack.count.coerceAtMost(TOTEM_RESERVE_CAPACITY))
        }
    }

    override fun equipmentContents(): NpcEquipmentSnapshot = NpcEquipmentSnapshot(
        mainHand = itemSnapshot(mainHandItem),
        offHand = itemSnapshot(offhandItem),
        head = itemSnapshot(super.getItemBySlot(EquipmentSlot.HEAD)),
        chest = itemSnapshot(super.getItemBySlot(EquipmentSlot.CHEST)),
        legs = itemSnapshot(super.getItemBySlot(EquipmentSlot.LEGS)),
        feet = itemSnapshot(super.getItemBySlot(EquipmentSlot.FEET)),
        ammunition = itemSnapshot(ammunition),
        totem = itemSnapshot(totem),
    )

    override fun equipmentKnowledge(): NpcEquipmentKnowledge = NpcEquipmentKnowledge(
        mainHand = NpcItemClassifier.profile(mainHandItem),
        offHand = NpcItemClassifier.profile(offhandItem),
        head = NpcItemClassifier.profile(super.getItemBySlot(EquipmentSlot.HEAD)),
        chest = NpcItemClassifier.profile(super.getItemBySlot(EquipmentSlot.CHEST)),
        legs = NpcItemClassifier.profile(super.getItemBySlot(EquipmentSlot.LEGS)),
        feet = NpcItemClassifier.profile(super.getItemBySlot(EquipmentSlot.FEET)),
        ammunition = NpcItemClassifier.profile(ammunition),
        totem = NpcItemClassifier.profile(totem),
    )

    override fun worldView(): NpcWorldView = NpcEntityWorldView(this)

    internal fun setInventoryStack(slot: Int, stack: ItemStack): NpcActionResult {
        if (slot !in inventory.indices) {
            return NpcActionResult.rejected("inventory slot must be between 0 and ${INVENTORY_SIZE - 1}")
        }
        replaceInventoryStack(slot, stack)
        return NpcActionResult.succeeded("inventory slot $slot updated")
    }

    internal fun clearInventoryStack(slot: Int): NpcActionResult = setInventoryStack(slot, ItemStack.EMPTY)

    override fun equipFromInventory(slot: Int, destination: NpcEquipmentDestination): NpcActionResult =
        equipFromInventory(slot, destination.toEquipmentSlot())

    internal fun equipFromInventory(slot: Int, destination: EquipmentSlot): NpcActionResult {
        if (slot !in inventory.indices) {
            return NpcActionResult.rejected("inventory slot must be between 0 and ${INVENTORY_SIZE - 1}")
        }
        val source = inventory[slot]
        if (source.isEmpty) {
            return NpcActionResult.rejected("inventory slot $slot is empty")
        }
        if (destination == EquipmentSlot.MAINHAND) {
            val selected = selectedHotbarSlot
            if (slot == selected) {
                return NpcActionResult.succeeded("inventory slot $slot is already in the main hand")
            }
            val previous = inventory[selected]
            inventory[selected] = source
            inventory[slot] = previous
            refreshMainHandAttributes()
            return NpcActionResult.succeeded("equipped inventory slot $slot in main hand")
        }
        if (destination.type == EquipmentSlot.Type.ARMOR && Mob.getEquipmentSlotForItem(source) != destination) {
            return NpcActionResult.rejected("${source.displayName.string} cannot be equipped in ${destination.name.lowercase()}")
        }
        val previous = super.getItemBySlot(destination)
        super.setItemSlot(destination, source)
        replaceInventoryStack(slot, previous)
        return NpcActionResult.succeeded("equipped inventory slot $slot in ${destination.name.lowercase()}")
    }

    override fun lookAtEntity(entityUuid: UUID): NpcActionResult {
        val entity = resolveEntity(entityUuid) ?: return NpcActionResult.rejected("entity is unavailable in this dimension")
        lookControl.setLookAt(entity, LOOK_YAW_SPEED, LOOK_PITCH_SPEED)
        return NpcActionResult.accepted("look control applied")
    }

    override fun setLookRotation(rotation: NpcLookRotation): NpcActionResult {
        if (!rotation.yaw.isFinite() || !rotation.pitch.isFinite()) {
            return NpcActionResult.rejected("look rotation must be finite")
        }
        val yaw = Mth.wrapDegrees(rotation.yaw)
        val pitch = Mth.clamp(rotation.pitch, MIN_PITCH, MAX_PITCH)
        setYRot(yaw)
        setXRot(pitch)
        yHeadRot = yaw
        yBodyRot = yaw
        return NpcActionResult.succeeded("look rotation applied")
    }

    override fun applyControl(input: NpcControlInput): NpcActionResult {
        if (!input.forward.isFinite() || !input.strafe.isFinite() || !input.speedMultiplier.isFinite()) {
            return NpcActionResult.rejected("control values must be finite", NpcActionCode.INVALID_REQUEST, NpcActionChannel.LOCOMOTION)
        }
        if (input.forward !in -1.0F..1.0F || input.strafe !in -1.0F..1.0F || input.speedMultiplier !in 0.0F..1.0F) {
            return NpcActionResult.rejected("control values are out of bounds", NpcActionCode.INVALID_REQUEST, NpcActionChannel.LOCOMOTION)
        }
        if (input.sprint && input.sneak) {
            return NpcActionResult.rejected("sprint and sneak cannot be active together", NpcActionCode.CONFLICT, NpcActionChannel.LOCOMOTION)
        }
        // Direct player-like controls explicitly replace a previously submitted path.
        navigationController.cancel(NpcActionCode.CANCELLED, "navigation stopped or replaced by direct control")
        navigation.stop()
        controlInput = input
        val previousAction = controlActionId
        val actionId = previousAction ?: UUID.randomUUID()
        controlActionId = actionId
        controlExpiresAt = level().gameTime + CONTROL_INPUT_TTL_TICKS
        return if (previousAction == null) NpcActionResult.accepted("control input applied for $CONTROL_INPUT_TTL_TICKS ticks", actionId, NpcActionChannel.LOCOMOTION)
        else NpcActionResult.running("control input renewed", actionId, NpcActionChannel.LOCOMOTION)
    }

    override fun navigateTo(position: NpcPosition, speedMultiplier: Float): NpcActionResult =
        navigateTo(NpcNavigationRequest(position, speedMultiplier))

    override fun navigateTo(request: NpcNavigationRequest): NpcActionResult {
        if (blockBreakController.isActive) {
            return NpcActionResult.rejected("abort block breaking before starting navigation", NpcActionCode.CONFLICT, NpcActionChannel.LOCOMOTION)
        }
        val result = navigationController.start(request)
        if (result.status == NpcActionStatus.REJECTED || result.status == NpcActionStatus.FAILED || result.status == NpcActionStatus.UNSUPPORTED) return result
        val replacedControlAction = controlActionId
        controlInput = NpcControlInput.IDLE
        controlActionId = null
        controlExpiresAt = Long.MIN_VALUE
        setXxa(0.0F)
        setZza(0.0F)
        setSprinting(request.speedMultiplier > 1.0F && navigationController.isActive)
        setShiftKeyDown(false)
        if (replacedControlAction != null) {
            completeAction(NpcActionResult.failed("direct control replaced by navigation", NpcActionCode.CANCELLED, replacedControlAction, NpcActionChannel.LOCOMOTION))
        }
        return result
    }

    override fun stopControl(): NpcActionResult {
        val actionId = controlActionId
        controlInput = NpcControlInput.IDLE
        controlActionId = null
        controlExpiresAt = Long.MIN_VALUE
        navigationController.cancel(NpcActionCode.CANCELLED, "navigation stopped")
        navigation.stop()
        setXxa(0.0F)
        setZza(0.0F)
        stopDirectMovement()
        setSprinting(false)
        setShiftKeyDown(false)
        if (actionId != null) {
            completeAction(NpcActionResult.succeeded("control input stopped", actionId, NpcActionChannel.LOCOMOTION))
        }
        return NpcActionResult.succeeded("control input stopped", actionId, NpcActionChannel.LOCOMOTION)
    }

    override fun jump(): NpcActionResult {
        if (onGround()) {
            jumpFromGround()
            return NpcActionResult.succeeded("jumped")
        }
        if (isInWater || isInLava) {
            deltaMovement = deltaMovement.add(0.0, SWIM_JUMP_IMPULSE, 0.0)
            return NpcActionResult.succeeded("swam upward")
        }
        if (onClimbable()) {
            deltaMovement = deltaMovement.add(0.0, CLIMB_JUMP_IMPULSE, 0.0)
            return NpcActionResult.succeeded("climbed upward")
        }
        return NpcActionResult.rejected("NPC cannot jump while airborne")
    }

    override fun selectHotbarSlot(slot: Int): NpcActionResult {
        if (slot !in 0 until HOTBAR_SIZE) {
            return NpcActionResult.rejected("hotbar slot must be between 0 and ${HOTBAR_SIZE - 1}")
        }
        selectedHotbarSlot = slot
        refreshMainHandAttributes()
        entityData.set(DATA_SELECTED_SLOT, slot.toByte())
        return NpcActionResult.succeeded("selected hotbar slot $slot")
    }

    override fun attackEntity(entityUuid: UUID): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        if (rangedController.isActive || blockBreakController.isActive || isUsingItem) {
            return NpcActionResult.rejected("finish the active hand action before performing melee", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        val target = resolveEntity(entityUuid) as? LivingEntity
            ?: return NpcActionResult.rejected("entity is unavailable in this dimension")
        val denied = NpcCombatRules.rejection(this, target)
        if (denied != null) return denied
        if (!target.isAlive || target.level() != level()) {
            return NpcActionResult.rejected("entity is no longer attackable")
        }
        if (distanceToSqr(target) > MELEE_REACH_SQR) {
            return NpcActionResult.rejected("attack target is out of melee reach")
        }
        if (!hasLineOfSight(target)) {
            return NpcActionResult.rejected("attack target is not visible")
        }

        val strength = attackStrengthScale()
        val stack = mainHandItem
        var damage = getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat()
        val enchantmentDamage = EnchantmentHelper.getDamageBonus(stack, target.mobType)
        damage *= 0.2F + strength * strength * 0.8F
        damage += enchantmentDamage * strength
        if (damage <= 0.0F) {
            return NpcActionResult.rejected("held item cannot deal damage")
        }

        val fullyCharged = strength > FULLY_CHARGED_THRESHOLD
        val critical = fullyCharged && fallDistance > 0.0F && !onGround() && !isSprinting && !isInWater && !hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) && !isPassenger
        if (critical) {
            damage *= CRITICAL_DAMAGE_MULTIPLIER
        }
        var knockback = EnchantmentHelper.getKnockbackBonus(this)
        val sprintKnockback = fullyCharged && isSprinting
        if (sprintKnockback) {
            knockback += 1
        }
        val fireAspect = EnchantmentHelper.getFireAspect(this)
        var temporaryFire = false
        if (fireAspect > 0 && !target.isOnFire) {
            target.setSecondsOnFire(1)
            temporaryFire = true
        }

        lastAttackGameTime = level().gameTime
        val damaged = target.hurt(damageSources().mobAttack(this), damage)
        if (!damaged) {
            if (temporaryFire) {
                target.clearFire()
            }
            startVisibleSwing()
            return NpcActionResult.failed("attack dealt no damage")
        }

        if (knockback > 0) {
            val yawRadians = yRot * (Math.PI / 180.0).toFloat()
            target.knockback((knockback * KNOCKBACK_STRENGTH).toDouble(), Mth.sin(yawRadians).toDouble(), (-Mth.cos(yawRadians)).toDouble())
            deltaMovement = deltaMovement.multiply(POST_HIT_HORIZONTAL_MOMENTUM, 1.0, POST_HIT_HORIZONTAL_MOMENTUM)
            if (sprintKnockback) {
                isSprinting = false
            }
        }
        EnchantmentHelper.doPostHurtEffects(target, this)
        EnchantmentHelper.doPostDamageEffects(this, target)
        if (fireAspect > 0) target.setSecondsOnFire(fireAspect * 4)
        if (!level().isClientSide && !stack.isEmpty) {
            // ItemStack's convenience wrapper requires Player; Item's hook accepts LivingEntity.
            damageMainHandAfterAttack(stack, target)
        }
        startVisibleSwing()
        val qualifier = if (critical) "critical " else ""
        return NpcActionResult.succeeded("${qualifier}attacked supplied entity at ${(strength * 100).toInt()}% strength")
    }

    override fun startRangedAttack(entityUuid: UUID, hand: NpcHand): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        if (blockBreakController.isActive || itemUseController.actionId != null) {
            return NpcActionResult.rejected("cancel the conflicting block or held action before ranged use", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        return rangedController.start(entityUuid, hand)
    }

    override fun cancelRangedAttack(): NpcActionResult = rangedController.cancel()

    override fun pickupItem(itemEntityUuid: UUID): NpcActionResult = inventoryActions.pickupItem(itemEntityUuid)

    override fun dropInventoryStack(slot: Int, count: Int): NpcActionResult = inventoryActions.dropInventoryStack(slot, count)

    override fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult =
        inventoryActions.moveInventoryToBlockContainer(inventorySlot, destination, count)

    override fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult =
        inventoryActions.moveBlockContainerToInventory(source, count)

    override fun transferToContainer(inventorySlot: Int, request: NpcContainerTransferRequest): NpcContainerTransferResult =
        inventoryActions.containerTransfers.insert(inventorySlot, request)

    override fun transferFromContainer(request: NpcContainerTransferRequest): NpcContainerTransferResult =
        inventoryActions.containerTransfers.extract(request)

    override fun containerTransferState(): NpcContainerTransferState? = inventoryActions.containerTransfers.journal.state

    override fun moveInventoryStack(sourceSlot: Int, destinationSlot: Int, count: Int): NpcActionResult {
        if (sourceSlot !in inventory.indices || destinationSlot !in inventory.indices) {
            return NpcActionResult.rejected("inventory slots must be between 0 and ${INVENTORY_SIZE - 1}", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INVENTORY)
        }
        if (sourceSlot == destinationSlot) {
            return NpcActionResult.rejected("source and destination inventory slots must differ", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INVENTORY)
        }
        if (count <= 0) {
            return NpcActionResult.rejected("transfer count must be positive", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INVENTORY)
        }
        val source = inventory[sourceSlot]
        if (source.isEmpty) {
            return NpcActionResult.rejected("source inventory slot $sourceSlot is empty", NpcActionCode.NOT_READY, NpcActionChannel.INVENTORY)
        }
        val destination = inventory[destinationSlot]
        if (!destination.isEmpty && !ItemStack.isSameItemSameTags(source, destination)) {
            return NpcActionResult.rejected("destination inventory slot contains a different item", NpcActionCode.CONFLICT, NpcActionChannel.INVENTORY)
        }
        val capacity = if (destination.isEmpty) source.maxStackSize else destination.maxStackSize - destination.count
        val transfer = minOf(count, source.count, capacity)
        if (transfer <= 0) {
            return NpcActionResult.rejected("destination inventory slot has no free capacity", NpcActionCode.CONFLICT, NpcActionChannel.INVENTORY)
        }
        val moved = source.copyWithCount(transfer)
        source.shrink(transfer)
        inventory[destinationSlot] = if (destination.isEmpty) moved else destination.copyWithCount(destination.count + transfer)
        replaceInventoryStack(sourceSlot, source)
        if (sourceSlot == selectedHotbarSlot || destinationSlot == selectedHotbarSlot) {
            refreshMainHandAttributes()
        }
        return NpcActionResult.succeeded("moved $transfer ${itemId(moved)} from inventory slot $sourceSlot to $destinationSlot", channel = NpcActionChannel.INVENTORY)
    }

    override fun swapInventorySlots(firstSlot: Int, secondSlot: Int): NpcActionResult {
        if (firstSlot !in inventory.indices || secondSlot !in inventory.indices) {
            return NpcActionResult.rejected("inventory slots must be between 0 and ${INVENTORY_SIZE - 1}", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INVENTORY)
        }
        if (firstSlot == secondSlot) {
            return NpcActionResult.succeeded("inventory slot $firstSlot is unchanged", channel = NpcActionChannel.INVENTORY)
        }
        val first = inventory[firstSlot]
        inventory[firstSlot] = inventory[secondSlot]
        inventory[secondSlot] = first
        if (firstSlot == selectedHotbarSlot || secondSlot == selectedHotbarSlot) {
            refreshMainHandAttributes()
        }
        return NpcActionResult.succeeded("swapped inventory slots $firstSlot and $secondSlot", channel = NpcActionChannel.INVENTORY)
    }

    override fun startBlockBreak(position: NpcBlockPosition): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        if (rangedController.isActive || isUsingItem) {
            return NpcActionResult.rejected("cancel the conflicting ranged or item use before breaking a block", NpcActionCode.CONFLICT, NpcActionChannel.BLOCK_ACTION)
        }
        return blockBreakController.start(position)
    }

    override fun continueBlockBreak(): NpcActionResult = blockBreakController.renew()

    override fun abortBlockBreak(): NpcActionResult = blockBreakController.cancel()

    /**
     * Held items with a vanilla duration (food, drink, shields and similar items) use the same
     * server-side held-use lifecycle as [startItemUse]. Instant Item.use paths require Player and
     * are reported as unsupported instead of passing a counterfeit player to mod code.
     */
    override fun useItemInAir(hand: NpcHand): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        if (rangedController.isActive) {
            return NpcActionResult.rejected("cancel the ranged attack before using another item", NpcActionCode.CONFLICT, hand.actionChannel())
        }
        val stack = getItemInHand(hand.toInteractionHand())
        if (stack.isEmpty) {
            return NpcActionResult.rejected("${hand.name.lowercase()} hand is empty", NpcActionCode.NOT_READY, hand.actionChannel())
        }
        if (stack.item is ThrowablePotionItem) {
            val projectile = ThrownPotion(level(), this)
            projectile.item = stack.copyWithCount(1)
            projectile.shootFromRotation(this, xRot, yRot, 0.0F, THROWN_POTION_SPEED, THROWN_POTION_INACCURACY)
            if (!level().addFreshEntity(projectile)) {
                return NpcActionResult.failed("could not spawn thrown potion", NpcActionCode.WORLD_REJECTED, channel = hand.actionChannel())
            }
            stack.shrink(1)
            if (hand == NpcHand.MAIN) {
                refreshMainHandAttributes()
            }
            return NpcActionResult.succeeded("threw ${itemId(stack)}", channel = hand.actionChannel())
        }
        if (stack.useDuration <= 0) {
            return NpcActionResult.unsupported(
                "${itemId(stack)} has an instant Item.use path that requires a real Player",
                hand.actionChannel(),
            )
        }
        return startItemUse(hand)
    }

    /**
     * Calls the held item's normal use-on-block method with the exact supplied hit. Forge and
     * vanilla items that only need a nullable player context (including ordinary BlockItem
     * placement) keep their own placement state/orientation logic. Player-hard-coupled items are
     * rejected explicitly rather than impersonating a ServerPlayer.
     */
    override fun useItemOnBlock(hit: NpcBlockHit, hand: NpcHand): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        if (rangedController.isActive) {
            return NpcActionResult.rejected("cancel the ranged attack before using an item on a block", NpcActionCode.CONFLICT, NpcActionChannel.INTERACTION)
        }
        if (isUsingItem) {
            return NpcActionResult.rejected("cancel item use before using an item on a block", NpcActionCode.CONFLICT, NpcActionChannel.INTERACTION)
        }
        val blockPos = BlockPos(hit.block.x, hit.block.y, hit.block.z)
        val location = Vec3(hit.location.x, hit.location.y, hit.location.z)
        if (distanceToSqr(location) > BLOCK_INTERACTION_REACH_SQR) {
            return NpcActionResult.rejected("block hit is out of interaction reach", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.INTERACTION)
        }
        if (location.distanceToSqr(blockPos.center) > MAX_HIT_OFFSET_SQR) {
            return NpcActionResult.rejected("block hit location is not on the supplied block", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INTERACTION)
        }
        val interactionHand = hand.toInteractionHand()
        val stack = getItemInHand(interactionHand)
        if (stack.isEmpty) {
            return NpcActionResult.rejected("${hand.name.lowercase()} hand is empty", NpcActionCode.NOT_READY, NpcActionChannel.INTERACTION)
        }
        val blockBeforeUse = level().getBlockState(blockPos)
        val vanillaHoe = stack.item is net.minecraft.world.item.HoeItem &&
            net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.item)?.namespace == "minecraft"
        val result = try {
            NpcToolDurability.perform(stack) {
                stack.useOn(UseOnContext(level(), null, interactionHand, stack, BlockHitResult(location, hit.face.toDirection(), blockPos, hit.insideBlock)))
            }
        } catch (error: ClassCastException) {
            return NpcActionResult.unsupported("${itemId(stack)} requires a real Player for block use", NpcActionChannel.INTERACTION)
        } catch (error: NullPointerException) {
            // A number of third-party items dereference UseOnContext.player. Keep their failure
            // contained and explicit; a dedicated NPC may not substitute a fake ServerPlayer.
            return NpcActionResult.unsupported("${itemId(stack)} requires a real Player for block use", NpcActionChannel.INTERACTION)
        }
        if (!result.consumesAction()) {
            return NpcActionResult.rejected("${itemId(stack)} did not accept the supplied block hit", NpcActionCode.WORLD_REJECTED, NpcActionChannel.INTERACTION)
        }
        // Vanilla HoeItem.useOn skips durability when its nullable Player is absent. The normal
        // soil callback already ran; charge its one real tool use on this living body (ADR 0044).
        if (vanillaHoe && level().getBlockState(blockPos) != blockBeforeUse) {
            NpcToolDurability.perform(stack) { stack.hurtAndBreak(1, this) { it.broadcastBreakEvent(interactionHand) } }
        }
        if (interactionHand == InteractionHand.MAIN_HAND) {
            refreshMainHandAttributes()
        }
        swing(interactionHand, true)
        return NpcActionResult.succeeded("used ${itemId(stack)} on supplied block hit", channel = NpcActionChannel.INTERACTION)
    }

    /**
     * Entity right-click hooks are Player-typed in vanilla/Forge. This bounded primitive still
     * validates target and hit context so Behavior never needs an entity escape hatch, then makes
     * the unsupported boundary explicit until Forge offers an entity-safe hook.
     */
    override fun interactEntity(hit: NpcEntityHit, hand: NpcHand): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        val target = resolveEntity(hit.entityUuid)
            ?: return NpcActionResult.rejected("entity is unavailable in this dimension", NpcActionCode.NOT_FOUND, NpcActionChannel.INTERACTION)
        if (target.uuid == uuid || target.isRemoved) {
            return NpcActionResult.rejected("supplied entity cannot be interacted with", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INTERACTION)
        }
        if (distanceToSqr(target) > ENTITY_INTERACTION_REACH_SQR) {
            return NpcActionResult.rejected("entity is out of interaction reach", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.INTERACTION)
        }
        val location = Vec3(hit.location.x, hit.location.y, hit.location.z)
        if (!target.boundingBox.inflate(ENTITY_HIT_TOLERANCE).contains(location)) {
            return NpcActionResult.rejected("entity hit location is not on the supplied entity", NpcActionCode.INVALID_REQUEST, NpcActionChannel.INTERACTION)
        }
        val stack = getItemInHand(hand.toInteractionHand())
        if (stack.isEmpty && target.isVehicle) {
            if (!startRiding(target, true)) {
                return NpcActionResult.rejected("supplied vehicle rejected the NPC passenger", NpcActionCode.WORLD_REJECTED, NpcActionChannel.INTERACTION)
            }
            return NpcActionResult.succeeded("mounted supplied vehicle", channel = NpcActionChannel.INTERACTION)
        }
        val item = if (stack.isEmpty) "empty hand" else itemId(stack)
        return NpcActionResult.unsupported(
            "$item entity interaction requires a real Player in vanilla/Forge",
            NpcActionChannel.INTERACTION,
        )
    }

    /**
     * Compatibility wrapper for the original target-block command/API. The actual work is now
     * delegated to the context-rich item-on-block primitive so BlockItem chooses orientation,
     * waterlogging, multi-block state and its own modded placement behavior.
     */
    override fun placeHeldBlock(placement: NpcBlockPlacement, hand: NpcHand): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        val target = BlockPos(placement.position.x, placement.position.y, placement.position.z)
        // Bound reads here; useItemOnBlock still validates the actual support hit at normal reach.
        if (distanceToSqr(target.center) > PLACEMENT_PREFLIGHT_REACH_SQR) {
            return NpcActionResult.rejected("placement target is out of interaction reach", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.BLOCK_ACTION)
        }
        val face = placement.againstFace.toDirection()
        val clicked = target.relative(face.opposite)
        if (!level().hasChunkAt(target) || !level().hasChunkAt(clicked)) {
            return NpcActionResult.rejected("placement target or support is unavailable", NpcActionCode.NOT_READY, NpcActionChannel.BLOCK_ACTION)
        }
        if (!level().getBlockState(target).canBeReplaced()) {
            return NpcActionResult.rejected("block position is not replaceable", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        }
        val stack = getItemInHand(hand.toInteractionHand())
        val blockItem = stack.item as? BlockItem
            ?: return NpcActionResult.rejected("held item is not a placeable block", NpcActionCode.INVALID_REQUEST, NpcActionChannel.BLOCK_ACTION)
        if (level().getBlockState(clicked).isAir) {
            return NpcActionResult.rejected("placement needs a non-air supporting block", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        }
        val hitLocation = NpcPlacementSurface.visiblePoint(this,clicked,face)
            ?: return NpcActionResult.rejected("placement support face is not visible from the NPC eye", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        val collision = blockItem.block.defaultBlockState().getCollisionShape(level(), target)
        if (!collision.isEmpty && collision.toAabbs().any { box ->
                boundingBox.intersects(box.move(target.x.toDouble(), target.y.toDouble(), target.z.toDouble()))
            }
        ) {
            return NpcActionResult.rejected("placed block would intersect the NPC body", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        }
        return useItemOnBlock(
            NpcBlockHit(
                block = NpcBlockPosition(clicked.x, clicked.y, clicked.z),
                face = placement.againstFace,
                location = NpcPosition(hitLocation.x, hitLocation.y, hitLocation.z),
            ),
            hand,
        )
    }

    override fun useInteractiveBlock(position: NpcBlockPosition): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        val blockPos = BlockPos(position.x, position.y, position.z)
        if (distanceToSqr(blockPos.center) > BLOCK_INTERACTION_REACH_SQR) {
            return NpcActionResult.rejected("block is out of interaction reach")
        }
        if (!level().hasChunkAt(blockPos)) return NpcActionResult.rejected("interactive block is not loaded",NpcActionCode.NOT_READY)
        val state = level().getBlockState(blockPos)
        if (state.isAir) return NpcActionResult.rejected("block is air")
        val block = state.block
        if (block is net.minecraft.world.level.block.SweetBerryBushBlock) {
            if (rangedController.isActive || blockBreakController.isActive || isUsingItem) return NpcActionResult.rejected("cancel conflicting hand actions before using a ripe bush",NpcActionCode.CONFLICT)
            return NpcBerryInteraction.use(this,blockPos,state)
        }
        when (block) {
            is DoorBlock -> block.setOpen(this, level(), state, blockPos, !block.isOpen(state))
            is ButtonBlock -> block.press(state, level(), blockPos)
            is LeverBlock -> block.pull(state, level(), blockPos)
            else -> return NpcActionResult.unsupported("${state.block.descriptionId} requires a Player-specific interaction path")
        }
        swing(InteractionHand.MAIN_HAND, true)
        return NpcActionResult.succeeded("used ${state.block.descriptionId}")
    }

    internal fun hasFishingHook(id: UUID): Boolean = fishingController.hasHook(id)
    override fun fishingState(): NpcFishingState? = fishingController.state()
    override fun castFishing(request: NpcFishingCast): NpcActionResult {
        if (rangedController.isActive || blockBreakController.isActive || isUsingItem) {
            return NpcActionResult.rejected("cancel conflicting hand actions before fishing", NpcActionCode.CONFLICT)
        }
        return fishingController.start(request)
    }
    override fun continueFishing(actionId: UUID): NpcActionResult = fishingController.renew(actionId)
    override fun reelFishing(actionId: UUID): NpcFishingReelResult = fishingController.reel(actionId)
    override fun cancelFishing(): NpcActionResult = fishingController.cancel()

    override fun startItemUse(hand: NpcHand): NpcActionResult {
        if (fishingController.isActive) return NpcActionResult.rejected("cancel fishing before another hand action", NpcActionCode.CONFLICT)
        if (rangedController.isActive || blockBreakController.isActive) {
            return NpcActionResult.rejected("cancel the conflicting ranged or block action before using an item", NpcActionCode.CONFLICT, hand.actionChannel())
        }
        return itemUseController.start(hand)
    }

    override fun continueItemUse(): NpcActionResult {
        if (rangedController.isActive) return rangedController.continuation()
        return itemUseController.renew()
    }

    override fun releaseItemUse(): NpcActionResult {
        if (rangedController.isActive) return rangedController.release()
        val invalid = itemUseController.beforeTick()
        if (invalid != null) return invalid
        if (!isUsingItem) return NpcActionResult.rejected("NPC is not using an item", NpcActionCode.NOT_READY)
        val stack = useItem
        val hand = usedItemHand
        val result = when (stack.item) {
            is BowItem, is CrossbowItem, is TridentItem -> rangedController.releaseHeld(stack, ticksUsingItem, hand)
            else -> {
                val consumable = stack.useAnimation == net.minecraft.world.item.UseAnim.EAT ||
                    stack.useAnimation == net.minecraft.world.item.UseAnim.DRINK
                releaseUsingItem()
                if (consumable) NpcActionResult.failed("consumable use released before completion", NpcActionCode.CANCELLED)
                else NpcActionResult.succeeded("released held item use")
            }
        }
        stopUsingItem()
        return itemUseController.finish(result)
    }

    override fun cancelItemUse(): NpcActionResult {
        if (rangedController.isActive) return cancelRangedAttack()
        return itemUseController.cancel()
    }

    internal fun finishItemUseByVanilla(itemBeforeUse: ItemStack, resultStack: ItemStack): ItemStack =
        itemUseController.markVanillaFinish(itemBeforeUse, resultStack)

    private fun applyControlInput() {
        // PathNavigation owns Mob's MoveControl and speed while a caller is renewing a route.
        // Applying the idle direct-control input here would reset that speed before every AI tick.
        if (navigationController.isActive) {
            setXxa(0.0F)
            setZza(0.0F)
            setShiftKeyDown(false)
            return
        }
        val input = controlInput
        setXxa(input.strafe)
        setZza(input.forward)
        setSprinting(input.sprint)
        setShiftKeyDown(input.sneak)
        val movementSpeed = getAttributeValue(Attributes.MOVEMENT_SPEED).toFloat()
        val sprintMultiplier = if (input.sprint) SPRINT_SPEED_MULTIPLIER else 1.0F
        val sneakMultiplier = if (input.sneak) SNEAK_SPEED_MULTIPLIER else 1.0F
        setSpeed(movementSpeed * input.speedMultiplier * sprintMultiplier * sneakMultiplier)
        submitDirectMovement(input)
    }

    /** Apply caller-supplied local input through LivingEntity's normal collision-aware movement. */
    private fun submitDirectMovement(input: NpcControlInput) {
        if (abs(input.forward) <= CONTROL_INPUT_EPSILON && abs(input.strafe) <= CONTROL_INPUT_EPSILON) {
            stopDirectMovement()
            return
        }
        moveRelative(speed, Vec3(input.strafe.toDouble(), 0.0, input.forward.toDouble()))
    }

    private fun stopDirectMovement() {
        deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
    }

    private fun expireControlIfNeeded() {
        val actionId = controlActionId ?: return
        if (level().gameTime <= controlExpiresAt) {
            return
        }
        controlInput = NpcControlInput.IDLE
        controlActionId = null
        controlExpiresAt = Long.MIN_VALUE
        navigation.stop()
        setXxa(0.0F)
        setZza(0.0F)
        stopDirectMovement()
        setSprinting(false)
        setShiftKeyDown(false)
        completeAction(
            NpcActionResult.failed(
                "control input expired without a renewal",
                NpcActionCode.EXPIRED,
                actionId,
                NpcActionChannel.LOCOMOTION,
            ),
        )
    }

    /** Every accepted long-running action gets a terminal result when this entity leaves runtime. */
    private fun cancelActiveActionsForRemoval(state: NpcLifecycleState) {
        val detail = "action cancelled because NPC became ${state.name.lowercase()}"
        navigationController.cancel(NpcActionCode.CANCELLED, detail)
        navigation.stop()
        val controlId = controlActionId
        if (controlId != null) {
            controlInput = NpcControlInput.IDLE
            controlActionId = null
            controlExpiresAt = Long.MIN_VALUE
            navigation.stop()
            completeAction(NpcActionResult.failed(detail, NpcActionCode.CANCELLED, controlId, NpcActionChannel.LOCOMOTION))
        }
        rangedController.cancel(detail)
        fishingController.cancel(detail, removal = true)
        itemUseController.cancel(detail)
        blockBreakController.cancel(detail)
    }

    private fun itemUseState(): NpcItemUseState? {
        if (!isUsingItem) {
            return null
        }
        val stack = useItem
        if (stack.isEmpty) {
            return null
        }
        val remainingTicks = useItemRemainingTicks.coerceAtLeast(0)
        return NpcItemUseState(
            hand = usedItemHand.toNpcHand(),
            itemId = itemId(stack),
            remainingTicks = remainingTicks,
            elapsedTicks = (stack.useDuration - remainingTicks).coerceAtLeast(0),
            actionId = rangedController.actionId ?: itemUseController.actionId,
            leaseExpiresAt = itemUseController.expiresAt,
        )
    }

    internal fun resolveBlockContainer(position: NpcBlockPosition): Container? {
        val blockPos = BlockPos(position.x, position.y, position.z)
        if (distanceToSqr(blockPos.center) > BLOCK_INTERACTION_REACH_SQR) {
            return null
        }
        return NpcBlockContainers.resolve(level(), blockPos)
    }

    /** Keep the vanilla animate packet path, with tracked state as a late-client fallback. */
    internal fun startVisibleSwing() {
        swing(InteractionHand.MAIN_HAND, true)
        if (!level().isClientSide) {
            val current = entityData.get(DATA_SWING_SEQUENCE)
            val next = if (current == Int.MAX_VALUE) 0 else current + 1
            entityData.set(DATA_SWING_SEQUENCE, next)
        }
    }

    private fun attackStrengthScale(): Float {
        if (lastAttackGameTime == Long.MIN_VALUE) {
            return 1.0F
        }
        val elapsed = level().gameTime - lastAttackGameTime
        return NpcAttackTiming.strength(elapsed, getAttributeValue(Attributes.ATTACK_SPEED))
    }

    private fun damageMainHandAfterAttack(stack: ItemStack, target: LivingEntity) {
        NpcToolDurability.perform(stack) { damageMainHandNormally(stack, target) }
    }

    private fun damageMainHandNormally(stack: ItemStack, target: LivingEntity) {
        if (!stack.item.hurtEnemy(stack, target, this)) {
            return
        }
        // The Item.hurtEnemy hook already pays vanilla sword/axe durability.
        refreshMainHandAttributes()
    }

    /**
     * BowItem.releaseUsing is hard-coupled to Player because it queries Player inventory. Core's
     * explicit ammunition reserve supplies that one missing resource while preserving arrow-item
     * projectile construction, charge, enchantments, durability and normal entity physics.
     */
    internal fun acceptReturningTrident(projectile: NpcThrownTridentEntity, stack: ItemStack): Boolean {
        if (!isAlive || isRemoved || projectile.level() != level() || projectile.owner !== this) return false
        if (!boundingBox.inflate(0.75).intersects(projectile.boundingBox)) return false
        if (stack.item !is TridentItem || stack.count != 1 || inventoryActions.insert(stack) != 1) return false
        take(projectile, 1)
        level().playSound(null, x, y, z, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, 1.0F)
        return true
    }

    private fun dropAndClearInventorySlot(slot: Int) {
        val stack = inventory[slot]
        if (stack.isEmpty) {
            return
        }
        if (spawnAtLocation(stack.copy()) != null) {
            inventory[slot] = ItemStack.EMPTY
        }
    }

    private fun dropAndClearEquipmentSlot(slot: EquipmentSlot) {
        val stack = super.getItemBySlot(slot)
        if (stack.isEmpty) {
            return
        }
        if (spawnAtLocation(stack.copy()) != null) {
            super.setItemSlot(slot, ItemStack.EMPTY)
        }
    }

    private fun dropAndClearReserve(isAmmunition: Boolean) {
        val stack = if (isAmmunition) ammunition else totem
        if (stack.isEmpty || spawnAtLocation(stack.copy()) == null) {
            return
        }
        if (isAmmunition) {
            ammunition = ItemStack.EMPTY
        } else {
            totem = ItemStack.EMPTY
        }
    }

    private fun replaceInventoryStack(slot: Int, stack: ItemStack) {
        inventory[slot] = stack.copy()
        if (slot == selectedHotbarSlot) {
            refreshMainHandAttributes()
        }
    }

    internal fun refreshMainHandAttributes() {
        val attributes = attributes
        attributes.removeAttributeModifiers(appliedMainHandStack.getAttributeModifiers(EquipmentSlot.MAINHAND))
        val current = mainHandItem
        attributes.addTransientAttributeModifiers(current.getAttributeModifiers(EquipmentSlot.MAINHAND))
        appliedMainHandStack = current.copy()
    }

    internal fun resolveEntity(id: UUID): Entity? {
        val serverLevel = level() as? ServerLevel ?: return null
        return serverLevel.getEntity(id)
    }

    private fun completeAction(result: NpcActionResult) {
        val actionId = result.actionId ?: return
        val history = ArrayList<NpcActionCompletion>(16)
        history.addAll(recentActionCompletions.takeLast(15))
        history.add(NpcActionCompletion(result, level().gameTime))
        recentActionCompletions = java.util.List.copyOf(history)
        if (result.status == io.samcnpc.core.api.NpcActionStatus.FAILED) {
            SamcnpcCore.LOGGER.warn(
                "NPC action failed npc={} action={} channel={} code={} detail={}",
                uuid,
                actionId,
                result.channel,
                result.code,
                result.detail,
            )
        }
        MinecraftForge.EVENT_BUS.post(NpcActionCompletedEvent(NpcHandle(uuid, name.string), result.copy(actionId = actionId)))
    }

    private fun hasAuthoritativeItems(): Boolean =
        inventory.any { !it.isEmpty } ||
            !offhandItem.isEmpty ||
            !super.getItemBySlot(EquipmentSlot.HEAD).isEmpty ||
            !super.getItemBySlot(EquipmentSlot.CHEST).isEmpty ||
            !super.getItemBySlot(EquipmentSlot.LEGS).isEmpty ||
            !super.getItemBySlot(EquipmentSlot.FEET).isEmpty ||
            !ammunition.isEmpty ||
            !totem.isEmpty

    private fun itemSnapshot(stack: ItemStack): NpcItemStackSnapshot =
        if (stack.isEmpty) {
            NpcItemStackSnapshot.EMPTY
        } else {
            NpcItemStackSnapshot(
                itemId = itemId(stack),
                count = stack.count,
                maxStackSize = stack.maxStackSize,
                damage = stack.damageValue,
                maxDamage = stack.maxDamage,
            )
        }

    private fun removalState(reason: RemovalReason): NpcLifecycleState = when {
        dismissalRequested -> NpcLifecycleState.DISMISSED
        reason.name == "KILLED" -> NpcLifecycleState.DEAD
        reason.name.startsWith("UNLOADED") -> NpcLifecycleState.UNLOADED
        else -> NpcLifecycleState.REMOVED
    }

    private fun NpcEquipmentDestination.toEquipmentSlot(): EquipmentSlot = when (this) {
        NpcEquipmentDestination.MAIN_HAND -> EquipmentSlot.MAINHAND
        NpcEquipmentDestination.OFF_HAND -> EquipmentSlot.OFFHAND
        NpcEquipmentDestination.HEAD -> EquipmentSlot.HEAD
        NpcEquipmentDestination.CHEST -> EquipmentSlot.CHEST
        NpcEquipmentDestination.LEGS -> EquipmentSlot.LEGS
        NpcEquipmentDestination.FEET -> EquipmentSlot.FEET
    }

    private fun NpcHand.actionChannel(): NpcActionChannel = when (this) {
        NpcHand.MAIN -> NpcActionChannel.MAIN_HAND
        NpcHand.OFF -> NpcActionChannel.OFF_HAND
    }

    internal fun itemId(stack: ItemStack): String =
        ForgeRegistries.ITEMS.getKey(stack.item)?.toString() ?: "minecraft:air"

    private fun NpcHand.toInteractionHand(): InteractionHand = when (this) {
        NpcHand.MAIN -> InteractionHand.MAIN_HAND
        NpcHand.OFF -> InteractionHand.OFF_HAND
    }

    private fun InteractionHand.toNpcHand(): NpcHand = when (this) {
        InteractionHand.MAIN_HAND -> NpcHand.MAIN
        InteractionHand.OFF_HAND -> NpcHand.OFF
    }

    private fun InteractionHand.equipmentSlot(): EquipmentSlot = when (this) {
        InteractionHand.MAIN_HAND -> EquipmentSlot.MAINHAND
        InteractionHand.OFF_HAND -> EquipmentSlot.OFFHAND
    }

    private fun NpcBlockFace.toDirection(): Direction = when (this) {
        NpcBlockFace.DOWN -> Direction.DOWN
        NpcBlockFace.UP -> Direction.UP
        NpcBlockFace.NORTH -> Direction.NORTH
        NpcBlockFace.SOUTH -> Direction.SOUTH
        NpcBlockFace.WEST -> Direction.WEST
        NpcBlockFace.EAST -> Direction.EAST
    }

    private fun syncBinding() {
        val binding = summonerBinding
        entityData.set(DATA_SUMMONER_UUID, java.util.Optional.ofNullable(binding?.summonerUuid))
        entityData.set(DATA_SUMMONER_NAME, binding?.lastKnownName.orEmpty())
    }

    private fun syncSkin() {
        val binding = skinBinding ?: return
        entityData.set(DATA_SKIN_VALUE, binding.textureValue.orEmpty())
        entityData.set(DATA_SKIN_SIGNATURE, binding.textureSignature.orEmpty())
        entityData.set(DATA_SKIN_MODEL, binding.model.ordinal.toByte())
        entityData.set(DATA_SKIN_REVISION, binding.revision)
    }

    fun clientSummonerUuid(): UUID? = entityData.get(DATA_SUMMONER_UUID).orElse(null)
    fun clientSummonerName(): String = entityData.get(DATA_SUMMONER_NAME)
    fun clientSkinValue(): String? = entityData.get(DATA_SKIN_VALUE).ifEmpty { null }
    fun clientSkinSignature(): String? = entityData.get(DATA_SKIN_SIGNATURE).ifEmpty { null }
    fun clientSkinModel(): PlayerSkinModel = if (entityData.get(DATA_SKIN_MODEL).toInt() == PlayerSkinModel.SLIM.ordinal) PlayerSkinModel.SLIM else PlayerSkinModel.CLASSIC
    fun clientSkinRevision(): String = entityData.get(DATA_SKIN_REVISION)
    fun clientSelectedHotbarSlot(): Int = entityData.get(DATA_SELECTED_SLOT).toInt()

    fun animationsEnabled(): Boolean = entityData.get(DATA_ANIMATIONS_ENABLED)

    internal fun setAnimationsEnabled(enabled: Boolean) {
        check(!level().isClientSide) { "Animation settings are server-authoritative" }
        entityData.set(DATA_ANIMATIONS_ENABLED, enabled)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt(KEY_DATA_VERSION, DATA_VERSION)
        tag.putUUID("samcnpcLife", lifeId)
        tag.putBoolean("samcnpcDeathHandled", deathEquipmentDropped)
        summonPoint?.let { tag.put("samcnpcSummonPoint", it.save()) }
        val binding = summonerBinding
        if (binding != null) {
            val bindingTag = CompoundTag()
            binding.save(bindingTag)
            tag.put(KEY_SUMMONER, bindingTag)
            val skinTag = CompoundTag()
            (skinBinding ?: SkinBinding.fallback(binding.summonerUuid, binding.lastKnownName)).save(skinTag)
            tag.put(KEY_SKIN, skinTag)
        }
        ContainerHelper.saveAllItems(tag, inventory)
        tag.put(KEY_AMMUNITION, ammunition.save(CompoundTag()))
        tag.put(KEY_TOTEM, totem.save(CompoundTag()))
        tag.putInt(KEY_SELECTED_SLOT, selectedHotbarSlot)
        inventoryActions.containerTransfers.journal.write(tag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        loadedInventory = null
        recentDamageEventId = null
        recentAttackerUuid = null
        recentHurtGameTime = Long.MIN_VALUE
        super.readAdditionalSaveData(tag)
        // Preserve the summoned-player invariant even for entities saved by an older release.
        setPersistenceRequired()
        setCanPickUpLoot(false)
        val dataVersion = tag.getInt(KEY_DATA_VERSION)
        if (dataVersion > DATA_VERSION) {
            return
        }
        summonerBinding = if (tag.contains(KEY_SUMMONER, CompoundTag.TAG_COMPOUND.toInt())) {
            SummonerBinding.load(tag.getCompound(KEY_SUMMONER))
        } else {
            null
        }
        lifeId = if (dataVersion >= 4 && tag.hasUUID("samcnpcLife")) tag.getUUID("samcnpcLife") else UUID.randomUUID()
        deathEquipmentDropped = dataVersion >= 4 && tag.getBoolean("samcnpcDeathHandled")
        summonPoint = io.samcnpc.core.health.NpcSummonPoint.load(tag.getCompound("samcnpcSummonPoint"))
        if (summonPoint == null && summonerBinding != null) {
            // Older saves never recorded the summon position. First loaded position is their migration anchor.
            summonPoint = io.samcnpc.core.health.NpcSummonPoint.capture(this)
            if (dataVersion >= 4) SamcnpcCore.LOGGER.warn("NPC {} had an invalid summon point; using its loaded position", uuid)
        }
        val binding = summonerBinding
        skinBinding = if (binding != null && tag.contains(KEY_SKIN, CompoundTag.TAG_COMPOUND.toInt())) {
            SkinBinding.load(tag.getCompound(KEY_SKIN), binding.summonerUuid, binding.lastKnownName)
        } else if (binding != null) {
            SkinBinding.fallback(binding.summonerUuid, binding.lastKnownName)
        } else {
            null
        }
        ContainerHelper.loadAllItems(tag, inventory)
        // v1-v4 have no transfer journal. v5 unresolved effects remain fenced after load.
        inventoryActions.containerTransfers.journal.read(tag,
            NpcContainerEndpoint(level().dimension().location().toString(), NpcBlockPosition(blockX, blockY, blockZ)))

        ammunition = loadOptionalStack(tag, KEY_AMMUNITION)
        val persistedTotem = loadOptionalStack(tag, KEY_TOTEM)
        totem = persistedTotem.copyWithCount(persistedTotem.count.coerceAtMost(TOTEM_RESERVE_CAPACITY))
        selectedHotbarSlot = Mth.clamp(tag.getInt(KEY_SELECTED_SLOT), 0, HOTBAR_SIZE - 1)
        refreshMainHandAttributes()
        syncBinding()
        syncSkin()
        entityData.set(DATA_SELECTED_SLOT, selectedHotbarSlot.toByte())
        if (!level().isClientSide) {
            // Passive pickup runs before NpcServerTickEvent. Preserve loaded facts separately
            // so callers do not mistake a new pickup for an inconsistent entity save.
            loadedInventory = NpcInventoryLoadSnapshot(UUID.randomUUID(), inventory.map(::itemSnapshot), equipmentContents())
        }
    }

    companion object {
        internal const val DATA_VERSION = 5
        private const val KEY_DATA_VERSION = "samcnpcDataVersion"
        private const val KEY_SUMMONER = "summoner"
        private const val KEY_SKIN = "skin"
        private const val KEY_SELECTED_SLOT = "selectedSlot"
        private const val KEY_AMMUNITION = "ammunition"
        private const val KEY_TOTEM = "totem"
        const val INVENTORY_SIZE = 36
        private const val HOTBAR_SIZE = 9
        private const val MAX_NAME_LENGTH = 64
        private const val LOOK_YAW_SPEED = 30.0F
        private const val LOOK_PITCH_SPEED = 30.0F
        private const val MIN_PITCH = -90.0F
        private const val MAX_PITCH = 90.0F
        private const val SPRINT_SPEED_MULTIPLIER = 1.3F
        private const val SNEAK_SPEED_MULTIPLIER = 0.3F
        private const val CONTROL_INPUT_TTL_TICKS = 2L
        private const val CONTROL_INPUT_EPSILON = 0.0001F
        private const val SWIM_JUMP_IMPULSE = 0.04
        private const val CLIMB_JUMP_IMPULSE = 0.2
        private const val MELEE_REACH_SQR = 3.0 * 3.0
        private const val FULLY_CHARGED_THRESHOLD = 0.9F
        private const val CRITICAL_DAMAGE_MULTIPLIER = 1.5F
        private const val KNOCKBACK_STRENGTH = 0.5F
        private const val POST_HIT_HORIZONTAL_MOMENTUM = 0.6
        private const val THROWN_POTION_SPEED = 0.5F
        private const val THROWN_POTION_INACCURACY = 1.0F
        private const val BLOCK_PLACE_REACH_SQR = 4.5 * 4.5
        private const val PLACEMENT_PREFLIGHT_REACH_SQR = 7.0 * 7.0
        internal const val BLOCK_INTERACTION_REACH_SQR = 4.5 * 4.5
        private const val ENTITY_INTERACTION_REACH_SQR = 4.5 * 4.5
        private const val ENTITY_HIT_TOLERANCE = 0.25
        private const val MAX_HIT_OFFSET_SQR = 1.5 * 1.5
        private const val BLOCK_UPDATE_FLAGS = 3
        private const val TOTEM_RESERVE_CAPACITY = 1

        const val EQUIPMENT_HEAD = 0
        const val EQUIPMENT_CHEST = 1
        const val EQUIPMENT_LEGS = 2
        const val EQUIPMENT_FEET = 3
        const val EQUIPMENT_MAIN_HAND = 4
        const val EQUIPMENT_OFF_HAND = 5
        const val EQUIPMENT_AMMUNITION = 6
        const val EQUIPMENT_TOTEM = 7
        const val EQUIPMENT_SLOT_COUNT = 8



        private fun loadOptionalStack(tag: CompoundTag, key: String): ItemStack =
            if (tag.contains(key, CompoundTag.TAG_COMPOUND.toInt())) ItemStack.of(tag.getCompound(key)) else ItemStack.EMPTY

        private val DATA_SUMMONER_UUID: EntityDataAccessor<java.util.Optional<UUID>> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)
        private val DATA_SUMMONER_NAME: EntityDataAccessor<String> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.STRING)
        private val DATA_SKIN_VALUE: EntityDataAccessor<String> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.STRING)
        private val DATA_SKIN_SIGNATURE: EntityDataAccessor<String> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.STRING)
        private val DATA_SKIN_MODEL: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.BYTE)
        private val DATA_SKIN_REVISION: EntityDataAccessor<String> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.STRING)
        private val DATA_SELECTED_SLOT: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.BYTE)
        private val DATA_ANIMATIONS_ENABLED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_SWING_SEQUENCE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(SamcnpcEntity::class.java, EntityDataSerializers.INT)

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.ATTACK_DAMAGE, 1.0)
            .add(Attributes.ATTACK_SPEED, 4.0)
            .add(Attributes.FOLLOW_RANGE, 32.0)
    }
}
