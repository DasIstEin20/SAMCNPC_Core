package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcAttackTiming
import io.samcnpc.core.api.NpcBlockBreakMath
import io.samcnpc.core.api.NpcMiningSpeed
import io.samcnpc.core.api.NpcBlockBreakState
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcBlockHit
import io.samcnpc.core.api.NpcEntityHit
import io.samcnpc.core.api.NpcBlockFace
import io.samcnpc.core.api.NpcBlockPlacement
import io.samcnpc.core.api.NpcBlockContainerSlot
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcEquipmentDestination
import io.samcnpc.core.api.NpcEquipmentSnapshot
import io.samcnpc.core.api.NpcEquipmentKnowledge
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
import io.samcnpc.core.api.NpcRangedAttackPhase
import io.samcnpc.core.api.NpcRangedAttackState
import io.samcnpc.core.api.NpcRangedWeaponKind
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
import net.minecraft.nbt.ListTag
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
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.ThrownTrident
import net.minecraft.world.entity.projectile.ThrownPotion
import net.minecraft.world.item.ArrowItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.ThrowablePotionItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.level.ClipContext
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.util.BlockSnapshot
import net.minecraftforge.event.ForgeEventFactory
import net.minecraftforge.registries.ForgeRegistries
import net.minecraft.world.item.enchantment.EnchantmentHelper
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

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
    private var controlInput: NpcControlInput = NpcControlInput.IDLE
    private var controlActionId: UUID? = null
    private var controlExpiresAt: Long = Long.MIN_VALUE
    private var activeItemUseActionId: UUID? = null
    private var activeItemUseChannel: NpcActionChannel? = null
    private var dismissalRequested: Boolean = false
    private var recentAttackerUuid: UUID? = null
    private var recentHurtGameTime: Long = Long.MIN_VALUE
    private var lastAttackGameTime: Long = Long.MIN_VALUE
    private var appliedMainHandStack: ItemStack = ItemStack.EMPTY
    private var removalAnnounced: Boolean = false
    private var deathEquipmentDropped: Boolean = false
    private var activeBlockBreak: ActiveBlockBreak? = null
    private var activeRangedAttack: ActiveRangedAttack? = null
    private var pendingNavigation: PendingNavigation? = null
    private var navigationExpiresAt: Long = Long.MIN_VALUE

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

    override fun createNavigation(level: Level): PathNavigation = super.createNavigation(level)

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
            if (isAlive && NpcSettingsConfig.enabled(NpcSetting.IMMORTAL)) health = maxHealth
            expireControlIfNeeded()
            applyControlInput()
        }
        super.tick()
        if (!level().isClientSide) {
            advanceNavigation()
            vacuumNearbyItems()
            advanceRangedAttack()
            completeNaturallyFinishedItemUse()
            advanceBlockBreak()
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
            }
            MinecraftForge.EVENT_BUS.post(NpcRemovedEvent(lifecycle))
        }
    }

    override fun onRemovedFromWorld() {
        super.onRemovedFromWorld()
        val server = (level() as? ServerLevel)?.server ?: return
        // Forge invokes this for tracking/unload paths that bypass Entity.remove().
        NpcActivityEvents.existing(server)?.leftWorld(this)
    }

    /**
     * A Core NPC is not a Player and therefore cannot retain inventory after its entity is gone.
     * Drop every authoritative Core store exactly once instead of inheriting Mob's random equipment
     * chances or silently losing the separate 36-slot inventory and reserves.
     */
    override fun dropEquipment() {
        if (deathEquipmentDropped) {
            return
        }
        deathEquipmentDropped = true
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

    override fun hurt(source: net.minecraft.world.damagesource.DamageSource, amount: Float): Boolean {
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null && NpcSettingsConfig.enabled(NpcSetting.IMMORTAL, !NpcHeartSettings.enabled(serverLevel.server))) {
            return false
        }
        val accepted = super.hurt(source, amount)
        if (accepted && !level().isClientSide) {
            val attacker = source.entity
            if (attacker != null && attacker.uuid != uuid) {
                recentAttackerUuid = attacker.uuid
                recentHurtGameTime = level().gameTime
            }
        }
        return accepted
    }

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

    override fun snapshot(): NpcSnapshot {
        val hurtAge = if (recentHurtGameTime == Long.MIN_VALUE) null else (level().gameTime - recentHurtGameTime).coerceAtLeast(0)
        val fraction = if (maxHealth <= 0.0F) 0.0 else (health / maxHealth).toDouble().coerceIn(0.0, 1.0)
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
            lastDamageSourceEntityUuid = recentAttackerUuid,
            lastDamageAgeTicks = hurtAge,
            healthFraction = fraction,
            gameTime = level().gameTime,
            attackStrength = attackStrengthScale(),
            itemUse = itemUseState(),
            blockBreak = blockBreakState(),
            rangedAttack = rangedAttackState(),
            equipment = equipmentKnowledge(),
            selectedHotbarSlot = selectedHotbarSlot,
            ignoreMissingMiningTool = NpcSettingsConfig.enabled(NpcSetting.IGNORE_MISSING_TOOL),
            bareHandsMiningOnly = NpcSettingsConfig.enabled(NpcSetting.BARE_HANDS_ONLY),
        )
    }

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
        pendingNavigation = null
        navigationExpiresAt = Long.MIN_VALUE
        navigation.stop()
        controlInput = input
        val actionId = controlActionId ?: UUID.randomUUID().also { controlActionId = it }
        controlExpiresAt = level().gameTime + CONTROL_INPUT_TTL_TICKS
        return NpcActionResult.accepted("control input applied for $CONTROL_INPUT_TTL_TICKS ticks", actionId, NpcActionChannel.LOCOMOTION)
    }

    override fun navigateTo(position: NpcPosition, speedMultiplier: Float): NpcActionResult {
        if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite() || !speedMultiplier.isFinite()) {
            return NpcActionResult.rejected("navigation position and speed must be finite", NpcActionCode.INVALID_REQUEST, NpcActionChannel.LOCOMOTION)
        }
        if (speedMultiplier !in MIN_NAVIGATION_SPEED_MULTIPLIER..MAX_NAVIGATION_SPEED_MULTIPLIER) {
            return NpcActionResult.rejected(
                "navigation speed multiplier must be between $MIN_NAVIGATION_SPEED_MULTIPLIER and $MAX_NAVIGATION_SPEED_MULTIPLIER",
                NpcActionCode.INVALID_REQUEST,
                NpcActionChannel.LOCOMOTION,
            )
        }
        if (distanceToSqr(position.x, position.y, position.z) > MAX_NAVIGATION_TARGET_DISTANCE_SQR) {
            return NpcActionResult.rejected("navigation target is outside the bounded Core path range", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.LOCOMOTION)
        }
        if (activeBlockBreak != null) {
            return NpcActionResult.rejected("abort block breaking before starting navigation", NpcActionCode.CONFLICT, NpcActionChannel.LOCOMOTION)
        }

        val replacedControlAction = controlActionId
        controlInput = NpcControlInput.IDLE
        controlActionId = null
        controlExpiresAt = Long.MIN_VALUE
        setXxa(0.0F)
        setZza(0.0F)
        setSprinting(speedMultiplier > 1.0F)
        setShiftKeyDown(false)
        if (replacedControlAction != null) {
            completeAction(NpcActionResult.succeeded("control input replaced by requested navigation", replacedControlAction, NpcActionChannel.LOCOMOTION))
        }
        pendingNavigation = PendingNavigation(position, speedMultiplier)
        navigationExpiresAt = level().gameTime + NAVIGATION_REQUEST_TTL_TICKS
        return if (navigation.moveTo(position.x, position.y, position.z, speedMultiplier.toDouble())) {
            NpcActionResult.accepted("navigation path submitted", channel = NpcActionChannel.LOCOMOTION)
        } else {
            // Ground navigation may be queried while an entity is airborne for a tick or while a
            // chunk's collision state is settling. Retain the caller's bounded request and retry
            // it after future entity ticks. The route still expires unless the caller refreshes it.
            NpcActionResult.accepted("navigation request queued until Core can create a path", channel = NpcActionChannel.LOCOMOTION)
        }
    }

    override fun stopControl(): NpcActionResult {
        val actionId = controlActionId
        controlInput = NpcControlInput.IDLE
        controlActionId = null
        controlExpiresAt = Long.MIN_VALUE
        pendingNavigation = null
        navigationExpiresAt = Long.MIN_VALUE
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
        if (activeRangedAttack != null) {
            return NpcActionResult.rejected("cancel the ranged attack before performing melee", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        val target = resolveEntity(entityUuid) as? LivingEntity
            ?: return NpcActionResult.rejected("entity is unavailable in this dimension")
        if (target.uuid == uuid) {
            return NpcActionResult.rejected("NPC cannot attack itself")
        }
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
        if (!level().isClientSide && !stack.isEmpty) {
            // ItemStack.hurtEnemy and post-hit enchantment helpers require Player. Item's
            // LivingEntity hook preserves ordinary weapon durability without a fake player.
            damageMainHandAfterAttack(stack, target)
        }
        startVisibleSwing()
        val qualifier = if (critical) "critical " else ""
        return NpcActionResult.succeeded("${qualifier}attacked supplied entity at ${(strength * 100).toInt()}% strength")
    }

    override fun startRangedAttack(entityUuid: UUID, hand: NpcHand): NpcActionResult {
        if (activeRangedAttack != null) {
            return NpcActionResult.rejected("NPC is already performing a ranged attack", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        if (activeBlockBreak != null) {
            return NpcActionResult.rejected("abort block breaking before starting a ranged attack", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        if (isUsingItem || activeItemUseActionId != null) {
            return NpcActionResult.rejected("cancel the current item use before starting a ranged attack", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        val target = resolveEntity(entityUuid) as? LivingEntity
            ?: return NpcActionResult.rejected("entity is unavailable in this dimension", NpcActionCode.NOT_FOUND, NpcActionChannel.COMBAT)
        val targetRejection = validateRangedTarget(target)
        if (targetRejection != null) {
            return targetRejection
        }
        val interactionHand = hand.toInteractionHand()
        val stack = getItemInHand(interactionHand)
        val weapon = rangedWeaponKind(stack)
            ?: return NpcActionResult.unsupported("${itemId(stack)} is not a supported ranged weapon", NpcActionChannel.COMBAT)
        val resourceRejection = validateRangedResources(stack, weapon)
        if (resourceRejection != null) {
            return resourceRejection
        }
        if (weapon == NpcRangedWeaponKind.TRIDENT && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, stack) > 0) {
            return NpcActionResult.unsupported("Riptide requires Player travel semantics and cannot be applied to a dedicated NPC", NpcActionChannel.COMBAT)
        }

        aimAtRangedTarget(target)
        val phase = if (weapon == NpcRangedWeaponKind.CROSSBOW && CrossbowItem.isCharged(stack)) {
            NpcRangedAttackPhase.READY_TO_FIRE
        } else {
            NpcRangedAttackPhase.CHARGING
        }
        val requiredTicks = requiredRangedChargeTicks(stack, weapon, phase)
        if (phase == NpcRangedAttackPhase.CHARGING) {
            startUsingItem(interactionHand)
        }
        val actionId = UUID.randomUUID()
        activeRangedAttack = ActiveRangedAttack(
            actionId = actionId,
            targetUuid = target.uuid,
            hand = hand,
            weapon = weapon,
            phase = phase,
            phaseStartedGameTime = level().gameTime,
            requiredChargeTicks = requiredTicks,
        )
        return NpcActionResult.accepted(
            "started ${weapon.name.lowercase()} attack against supplied entity",
            actionId,
            NpcActionChannel.COMBAT,
        )
    }

    override fun cancelRangedAttack(): NpcActionResult {
        val action = activeRangedAttack
            ?: return NpcActionResult.rejected("NPC is not performing a ranged attack", NpcActionCode.NOT_READY, NpcActionChannel.COMBAT)
        val result = NpcActionResult.failed(
            "ranged attack cancelled",
            NpcActionCode.CANCELLED,
            action.actionId,
            NpcActionChannel.COMBAT,
        )
        finishRangedAttack(action, result)
        return result
    }

    override fun pickupItem(itemEntityUuid: UUID): NpcActionResult {
        val itemEntity = resolveEntity(itemEntityUuid) as? ItemEntity
            ?: return NpcActionResult.rejected("item entity is unavailable in this dimension")
        if (itemEntity.hasPickUpDelay()) {
            return NpcActionResult.rejected("item entity cannot be picked up yet")
        }
        if (distanceToSqr(itemEntity) > PICKUP_REACH_SQR) {
            return NpcActionResult.rejected("item entity is out of pickup reach")
        }
        val source = itemEntity.item
        if (source.isEmpty) {
            itemEntity.discard()
            return NpcActionResult.rejected("item entity is empty")
        }
        val remaining = source.copy()
        val pickedCount = insertIntoInventory(remaining)
        if (pickedCount == 0) {
            return NpcActionResult.rejected("NPC inventory has no room for ${itemId(source)}")
        }
        itemEntity.item = remaining
        take(itemEntity, pickedCount)
        level().playSound(
            null,
            x,
            y,
            z,
            SoundEvents.ITEM_PICKUP,
            SoundSource.PLAYERS,
            PICKUP_SOUND_VOLUME,
            ((random.nextFloat() - random.nextFloat()) * PICKUP_SOUND_VARIATION + PICKUP_SOUND_BASE_PITCH) * PICKUP_SOUND_PITCH_MULTIPLIER,
        )
        if (remaining.isEmpty) {
            itemEntity.discard()
        }
        return NpcActionResult.succeeded("picked up $pickedCount ${itemId(source)}")
    }

    override fun dropInventoryStack(slot: Int, count: Int): NpcActionResult {
        if (slot !in inventory.indices) {
            return NpcActionResult.rejected("inventory slot must be between 0 and ${INVENTORY_SIZE - 1}")
        }
        if (count <= 0) {
            return NpcActionResult.rejected("drop count must be positive")
        }
        val source = inventory[slot]
        if (source.isEmpty) {
            return NpcActionResult.rejected("inventory slot $slot is empty")
        }
        val dropCount = minOf(count, source.count)
        val dropped = source.copy()
        dropped.count = dropCount
        val itemEntity = ItemEntity(level(), x, y + DROP_HEIGHT_OFFSET, z, dropped)
        if (!level().addFreshEntity(itemEntity)) {
            return NpcActionResult.failed("could not spawn dropped item")
        }
        source.shrink(dropCount)
        replaceInventoryStack(slot, source)
        return NpcActionResult.succeeded("dropped $dropCount ${itemId(dropped)} from inventory slot $slot")
    }

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
        if (activeRangedAttack != null) {
            return NpcActionResult.rejected("cancel the ranged attack before breaking a block", NpcActionCode.CONFLICT, NpcActionChannel.BLOCK_ACTION)
        }
        if (activeBlockBreak != null) {
            return NpcActionResult.rejected("NPC is already breaking a block", NpcActionCode.CONFLICT, NpcActionChannel.BLOCK_ACTION)
        }
        if (isUsingItem) {
            return NpcActionResult.rejected("cancel item use before breaking a block", NpcActionCode.CONFLICT, NpcActionChannel.BLOCK_ACTION)
        }
        val blockPos = BlockPos(position.x, position.y, position.z)
        val state = level().getBlockState(blockPos)
        val rejection = validateBlockBreak(blockPos, state)
        if (rejection != null) {
            return rejection
        }
        val toolRejection = prepareMiningTool(state)
        if (toolRejection != null) {
            return toolRejection
        }
        val actionId = UUID.randomUUID()
        activeBlockBreak = ActiveBlockBreak(actionId, blockPos, 0.0F)
        publishBlockBreakProgress(blockPos, 0)
        startVisibleSwing()
        return NpcActionResult.accepted("started breaking ${state.block.descriptionId} at ${position.x}, ${position.y}, ${position.z}", actionId, NpcActionChannel.BLOCK_ACTION)
    }

    override fun continueBlockBreak(): NpcActionResult =
        activeBlockBreak?.let { NpcActionResult.running("block break is in progress", it.actionId, NpcActionChannel.BLOCK_ACTION) }
            ?: NpcActionResult.rejected("NPC is not breaking a block", NpcActionCode.NOT_READY, NpcActionChannel.BLOCK_ACTION)

    override fun abortBlockBreak(): NpcActionResult {
        val action = activeBlockBreak ?: return NpcActionResult.rejected("NPC is not breaking a block", NpcActionCode.NOT_READY, NpcActionChannel.BLOCK_ACTION)
        clearBlockBreak(action, NpcActionResult.failed("block break cancelled", NpcActionCode.CANCELLED, action.actionId, NpcActionChannel.BLOCK_ACTION))
        return NpcActionResult.succeeded("aborted block break", action.actionId, NpcActionChannel.BLOCK_ACTION)
    }

    /**
     * Held items with a vanilla duration (food, drink, shields and similar items) use the same
     * server-side held-use lifecycle as [startItemUse]. Instant Item.use paths require Player and
     * are reported as unsupported instead of passing a counterfeit player to mod code.
     */
    override fun useItemInAir(hand: NpcHand): NpcActionResult {
        if (activeRangedAttack != null) {
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
        if (activeRangedAttack != null) {
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
        val result = try {
            stack.useOn(UseOnContext(level(), null, interactionHand, stack, BlockHitResult(location, hit.face.toDirection(), blockPos, hit.insideBlock)))
        } catch (error: ClassCastException) {
            return NpcActionResult.unsupported("${itemId(stack)} requires a real Player for block use", NpcActionChannel.INTERACTION)
        } catch (error: NullPointerException) {
            // A number of third-party items dereference UseOnContext.player. Keep their failure
            // contained and explicit; a dedicated NPC may not substitute a fake ServerPlayer.
            return NpcActionResult.unsupported("${itemId(stack)} requires a real Player for block use", NpcActionChannel.INTERACTION)
        }
        if (result == InteractionResult.PASS) {
            return NpcActionResult.rejected("${itemId(stack)} did not handle the supplied block hit", NpcActionCode.WORLD_REJECTED, NpcActionChannel.INTERACTION)
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
        val target = BlockPos(placement.position.x, placement.position.y, placement.position.z)
        if (!level().getBlockState(target).canBeReplaced()) {
            return NpcActionResult.rejected("block position is not replaceable", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        }
        val stack = getItemInHand(hand.toInteractionHand())
        val blockItem = stack.item as? BlockItem
            ?: return NpcActionResult.rejected("held item is not a placeable block", NpcActionCode.INVALID_REQUEST, NpcActionChannel.BLOCK_ACTION)
        val face = placement.againstFace.toDirection()
        val clicked = target.relative(face.opposite)
        if (level().getBlockState(clicked).isAir) {
            return NpcActionResult.rejected("placement needs a non-air supporting block", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        }
        // Aim a hair inside the clicked block instead of ending exactly on the shared voxel
        // boundary. Clip may otherwise classify a downward pillar ray as a miss even though a
        // normal player is plainly looking at the support's top face.
        val hitLocation = clicked.center.add(
            face.stepX * (0.5 - PLACEMENT_FACE_INSET),
            face.stepY * (0.5 - PLACEMENT_FACE_INSET),
            face.stepZ * (0.5 - PLACEMENT_FACE_INSET),
        )
        val directHit = level().clip(
            ClipContext(
                eyePosition,
                hitLocation,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                this,
            ),
        )
        if (directHit.type != HitResult.Type.BLOCK || directHit.blockPos != clicked) {
            return NpcActionResult.rejected("placement support is not visible from the NPC eye", NpcActionCode.WORLD_REJECTED, NpcActionChannel.BLOCK_ACTION)
        }
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
        val blockPos = BlockPos(position.x, position.y, position.z)
        val state = level().getBlockState(blockPos)
        if (state.isAir) {
            return NpcActionResult.rejected("block is air")
        }
        if (distanceToSqr(blockPos.center) > BLOCK_INTERACTION_REACH_SQR) {
            return NpcActionResult.rejected("block is out of interaction reach")
        }
        val block = state.block
        when (block) {
            is DoorBlock -> block.setOpen(this, level(), state, blockPos, !block.isOpen(state))
            is ButtonBlock -> block.press(state, level(), blockPos)
            is LeverBlock -> block.pull(state, level(), blockPos)
            else -> return NpcActionResult.unsupported("${state.block.descriptionId} requires a Player-specific interaction path")
        }
        swing(InteractionHand.MAIN_HAND, true)
        return NpcActionResult.succeeded("used ${state.block.descriptionId}")
    }

    override fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (inventorySlot !in inventory.indices) {
            return NpcActionResult.rejected("inventory slot must be between 0 and ${INVENTORY_SIZE - 1}")
        }
        if (count <= 0) {
            return NpcActionResult.rejected("transfer count must be positive")
        }
        val source = inventory[inventorySlot]
        if (source.isEmpty) {
            return NpcActionResult.rejected("inventory slot $inventorySlot is empty")
        }
        val container = resolveBlockContainer(destination.position) ?: return NpcActionResult.rejected("no usable block container at supplied position")
        if (destination.slot !in 0 until container.containerSize) {
            return NpcActionResult.rejected("container slot is out of bounds")
        }
        if (!container.canPlaceItem(destination.slot, source)) {
            return NpcActionResult.rejected("container rejected this item")
        }
        val target = container.getItem(destination.slot)
        if (!target.isEmpty && !ItemStack.isSameItemSameTags(source, target)) {
            return NpcActionResult.rejected("container slot holds a different item")
        }
        val capacity = if (target.isEmpty) minOf(container.maxStackSize, source.maxStackSize) else minOf(container.maxStackSize, target.maxStackSize) - target.count
        val transfer = minOf(count, source.count, capacity)
        if (transfer <= 0) {
            return NpcActionResult.rejected("container slot has no free capacity")
        }
        val placed = if (target.isEmpty) source.copy() else target.copy()
        placed.count = if (target.isEmpty) transfer else target.count + transfer
        container.setItem(destination.slot, placed)
        container.setChanged()
        source.shrink(transfer)
        replaceInventoryStack(inventorySlot, source)
        return NpcActionResult.succeeded("moved $transfer ${itemId(placed)} to block container")
    }

    override fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (count <= 0) {
            return NpcActionResult.rejected("transfer count must be positive")
        }
        val container = resolveBlockContainer(source.position) ?: return NpcActionResult.rejected("no usable block container at supplied position")
        if (source.slot !in 0 until container.containerSize) {
            return NpcActionResult.rejected("container slot is out of bounds")
        }
        val stack = container.getItem(source.slot)
        if (stack.isEmpty) {
            return NpcActionResult.rejected("container slot is empty")
        }
        val proposed = stack.copy()
        proposed.count = minOf(count, stack.count)
        val accepted = insertIntoInventory(proposed)
        if (accepted <= 0) {
            return NpcActionResult.rejected("NPC inventory has no room for ${itemId(stack)}")
        }
        stack.shrink(accepted)
        container.setItem(source.slot, stack)
        container.setChanged()
        return NpcActionResult.succeeded("moved $accepted ${itemId(proposed)} from block container")
    }

    override fun startItemUse(hand: NpcHand): NpcActionResult {
        if (activeRangedAttack != null) {
            return NpcActionResult.rejected("cancel the ranged attack before starting independent item use", NpcActionCode.CONFLICT, hand.actionChannel())
        }
        if (isUsingItem) {
            return NpcActionResult.rejected("NPC is already using an item", NpcActionCode.CONFLICT, hand.actionChannel())
        }
        val interactionHand = hand.toInteractionHand()
        val stack = getItemInHand(interactionHand)
        if (stack.isEmpty) {
            return NpcActionResult.rejected("${hand.name.lowercase()} hand is empty", NpcActionCode.NOT_READY, hand.actionChannel())
        }
        val duration = stack.useDuration
        if (duration <= 0) {
            return NpcActionResult.unsupported("${itemId(stack)} has no held-use lifecycle", hand.actionChannel())
        }
        // A dedicated Mob cannot invoke Item.use(), whose contract requires a real Player.
        // LivingEntity's held-use lifecycle still runs vanilla tick, finish, and release hooks.
        startUsingItem(interactionHand)
        val actionId = UUID.randomUUID()
        activeItemUseActionId = actionId
        activeItemUseChannel = hand.actionChannel()
        return NpcActionResult.accepted("started using ${itemId(stack)} in ${hand.name.lowercase()} hand", actionId, activeItemUseChannel)
    }

    override fun continueItemUse(): NpcActionResult =
        if (isUsingItem) NpcActionResult.running("item use is in progress", activeItemUseActionId, usedItemHand.toNpcHand().actionChannel())
        else NpcActionResult.rejected("NPC is not using an item", NpcActionCode.NOT_READY)

    override fun releaseItemUse(): NpcActionResult {
        val ranged = activeRangedAttack
        if (ranged != null) {
            return releaseRangedAttackPhase(ranged, forced = true)
        }
        if (!isUsingItem) {
            return NpcActionResult.rejected("NPC is not using an item", NpcActionCode.NOT_READY)
        }
        val actionId = activeItemUseActionId
        val channel = usedItemHand.toNpcHand().actionChannel()
        if (useItem.item is BowItem) {
            val result = releaseBow(useItem, ticksUsingItem, usedItemHand)
            stopUsingItem()
            activeItemUseActionId = null
            activeItemUseChannel = null
            val completed = result.copy(actionId = actionId, channel = channel)
            completeAction(completed)
            return completed
        }
        if (useItem.item is CrossbowItem) {
            val result = releaseCrossbow(useItem, ticksUsingItem, usedItemHand)
            stopUsingItem()
            activeItemUseActionId = null
            activeItemUseChannel = null
            val completed = result.copy(actionId = actionId, channel = channel)
            completeAction(completed)
            return completed
        }
        if (useItem.item is TridentItem) {
            val result = releaseTrident(useItem, ticksUsingItem, usedItemHand)
            stopUsingItem()
            activeItemUseActionId = null
            activeItemUseChannel = null
            val completed = result.copy(actionId = actionId, channel = channel)
            completeAction(completed)
            return completed
        }
        releaseUsingItem()
        activeItemUseActionId = null
        activeItemUseChannel = null
        val completed = NpcActionResult.succeeded("released item use", actionId, channel)
        completeAction(completed)
        return completed
    }

    override fun cancelItemUse(): NpcActionResult {
        if (activeRangedAttack != null) {
            return cancelRangedAttack()
        }
        if (!isUsingItem) {
            return NpcActionResult.rejected("NPC is not using an item", NpcActionCode.NOT_READY)
        }
        val actionId = activeItemUseActionId
        val channel = usedItemHand.toNpcHand().actionChannel()
        stopUsingItem()
        activeItemUseActionId = null
        activeItemUseChannel = null
        val completed = NpcActionResult.failed("cancelled item use", NpcActionCode.CANCELLED, actionId, channel)
        completeAction(completed)
        return completed
    }

    private fun applyControlInput() {
        // PathNavigation owns Mob's MoveControl and speed while a caller is renewing a route.
        // Applying the idle direct-control input here would reset that speed before every AI tick.
        if (pendingNavigation != null) {
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

    /** Native navigation is a low-level movement mechanism; it has no autonomous destination. */
    private fun advanceNavigation() {
        val request = pendingNavigation ?: return
        if (level().gameTime > navigationExpiresAt) {
            pendingNavigation = null
            navigationExpiresAt = Long.MIN_VALUE
            navigation.stop()
            return
        }
        if (navigation.isDone) {
            navigation.moveTo(request.position.x, request.position.y, request.position.z, request.speedMultiplier.toDouble())
        }
    }

    private fun expireControlIfNeeded() {
        val actionId = controlActionId ?: return
        if (level().gameTime <= controlExpiresAt) {
            return
        }
        controlInput = NpcControlInput.IDLE
        controlActionId = null
        controlExpiresAt = Long.MIN_VALUE
        pendingNavigation = null
        navigationExpiresAt = Long.MIN_VALUE
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

    /**
     * Player inventory pickup is a contact mechanic, not a goal. Core never walks toward loot and
     * never equips it, but an ItemEntity entering the normal personal pickup envelope is inserted
     * into the authoritative 36-slot inventory just as it would be for a nearby player.
     */
    private fun vacuumNearbyItems() {
        if (!isAlive) {
            return
        }
        val nearby = level().getEntitiesOfClass(ItemEntity::class.java, boundingBox.inflate(PASSIVE_PICKUP_RADIUS))
            .asSequence()
            .filter { item -> !item.hasPickUpDelay() && !item.item.isEmpty }
            .sortedWith(compareBy<ItemEntity>({ distanceToSqr(it) }, { it.id }))
            .take(MAX_PASSIVE_PICKUPS_PER_TICK)
            .toList()
        for (item in nearby) {
            pickupItem(item.uuid)
        }
    }

    private fun advanceRangedAttack() {
        val action = activeRangedAttack ?: return
        val target = resolveEntity(action.targetUuid) as? LivingEntity
        if (target == null) {
            finishRangedAttack(
                action,
                NpcActionResult.failed("ranged target is no longer loaded", NpcActionCode.NOT_FOUND, action.actionId, NpcActionChannel.COMBAT),
            )
            return
        }
        val targetRejection = validateRangedTarget(target)
        if (targetRejection != null) {
            finishRangedAttack(
                action,
                NpcActionResult.failed(targetRejection.detail, targetRejection.code, action.actionId, NpcActionChannel.COMBAT),
            )
            return
        }
        val stack = getItemInHand(action.hand.toInteractionHand())
        if (rangedWeaponKind(stack) != action.weapon) {
            finishRangedAttack(
                action,
                NpcActionResult.failed("ranged weapon changed while the action was active", NpcActionCode.CONFLICT, action.actionId, NpcActionChannel.COMBAT),
            )
            return
        }
        aimAtRangedTarget(target)
        val elapsed = rangedPhaseElapsedTicks(action)
        if (action.phase == NpcRangedAttackPhase.CHARGING && elapsed < action.requiredChargeTicks) {
            return
        }
        releaseRangedAttackPhase(action, forced = false)
    }

    private fun releaseRangedAttackPhase(action: ActiveRangedAttack, forced: Boolean): NpcActionResult {
        if (activeRangedAttack !== action) {
            return NpcActionResult.rejected("ranged action is no longer active", NpcActionCode.NOT_READY, NpcActionChannel.COMBAT)
        }
        val target = resolveEntity(action.targetUuid) as? LivingEntity
        if (target == null) {
            val failed = NpcActionResult.failed("ranged target is no longer loaded", NpcActionCode.NOT_FOUND, action.actionId, NpcActionChannel.COMBAT)
            finishRangedAttack(action, failed)
            return failed
        }
        aimAtRangedTarget(target)
        val elapsed = rangedPhaseElapsedTicks(action)
        if (!forced && action.phase == NpcRangedAttackPhase.CHARGING && elapsed < action.requiredChargeTicks) {
            return NpcActionResult.running("ranged weapon is still charging", action.actionId, NpcActionChannel.COMBAT)
        }
        val hand = action.hand.toInteractionHand()
        val stack = getItemInHand(hand)
        val result = when (action.weapon) {
            NpcRangedWeaponKind.BOW -> releaseBow(stack, elapsed, hand)
            NpcRangedWeaponKind.TRIDENT -> releaseTrident(stack, elapsed, hand)
            NpcRangedWeaponKind.CROSSBOW -> releaseCrossbow(stack, elapsed, hand)
        }

        if (action.weapon == NpcRangedWeaponKind.CROSSBOW && action.phase == NpcRangedAttackPhase.CHARGING && result.status == NpcActionStatus.SUCCEEDED && CrossbowItem.isCharged(stack)) {
            if (isUsingItem) {
                stopUsingItem()
            }
            action.phase = NpcRangedAttackPhase.READY_TO_FIRE
            action.phaseStartedGameTime = level().gameTime
            action.requiredChargeTicks = 0
            return NpcActionResult.running("crossbow loaded and ready to fire", action.actionId, NpcActionChannel.COMBAT)
        }

        val terminal = result.copy(actionId = action.actionId, channel = NpcActionChannel.COMBAT)
        finishRangedAttack(action, terminal)
        return terminal
    }

    private fun finishRangedAttack(action: ActiveRangedAttack, result: NpcActionResult) {
        if (activeRangedAttack !== action) {
            return
        }
        if (isUsingItem && usedItemHand == action.hand.toInteractionHand()) {
            stopUsingItem()
        }
        activeRangedAttack = null
        completeAction(result.copy(actionId = action.actionId, channel = NpcActionChannel.COMBAT))
    }

    private fun validateRangedTarget(target: LivingEntity): NpcActionResult? {
        if (target.uuid == uuid) {
            return NpcActionResult.rejected("NPC cannot attack itself", NpcActionCode.INVALID_REQUEST, NpcActionChannel.COMBAT)
        }
        if (!target.isAlive || target.level() != level()) {
            return NpcActionResult.rejected("entity is no longer attackable", NpcActionCode.NOT_FOUND, NpcActionChannel.COMBAT)
        }
        if (distanceToSqr(target) > RANGED_REACH_SQR) {
            return NpcActionResult.rejected("ranged target is out of supported reach", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.COMBAT)
        }
        if (!hasLineOfSight(target)) {
            return NpcActionResult.rejected("ranged target is not visible", NpcActionCode.WORLD_REJECTED, NpcActionChannel.COMBAT)
        }
        return null
    }

    private fun validateRangedResources(stack: ItemStack, weapon: NpcRangedWeaponKind): NpcActionResult? = when (weapon) {
        NpcRangedWeaponKind.BOW -> if (findArrowAmmunition() == null) {
            NpcActionResult.rejected("bow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        } else {
            null
        }
        NpcRangedWeaponKind.CROSSBOW -> if (!CrossbowItem.isCharged(stack) && findArrowAmmunition() == null) {
            NpcActionResult.rejected("crossbow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        } else {
            null
        }
        NpcRangedWeaponKind.TRIDENT -> null
    }

    private fun rangedWeaponKind(stack: ItemStack): NpcRangedWeaponKind? = when (stack.item) {
        is BowItem -> NpcRangedWeaponKind.BOW
        is CrossbowItem -> NpcRangedWeaponKind.CROSSBOW
        is TridentItem -> NpcRangedWeaponKind.TRIDENT
        else -> null
    }

    private fun requiredRangedChargeTicks(
        stack: ItemStack,
        weapon: NpcRangedWeaponKind,
        phase: NpcRangedAttackPhase,
    ): Int = when {
        phase == NpcRangedAttackPhase.READY_TO_FIRE -> 0
        weapon == NpcRangedWeaponKind.BOW -> BOW_FULL_CHARGE_TICKS
        weapon == NpcRangedWeaponKind.CROSSBOW -> crossbowChargeTicks(stack)
        else -> MIN_TRIDENT_CHARGE_TICKS
    }

    private fun aimAtRangedTarget(target: LivingEntity) {
        val origin = eyePosition
        val destination = target.eyePosition
        val dx = destination.x - origin.x
        val dy = destination.y - origin.y
        val dz = destination.z - origin.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90.0F
        val pitch = -Math.toDegrees(atan2(dy, horizontal)).toFloat()
        setLookRotation(NpcLookRotation(yaw, pitch))
    }

    private fun rangedPhaseElapsedTicks(action: ActiveRangedAttack): Int =
        (level().gameTime - action.phaseStartedGameTime).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun completeNaturallyFinishedItemUse() {
        val actionId = activeItemUseActionId ?: return
        if (isUsingItem) {
            return
        }
        activeItemUseActionId = null
        val channel = activeItemUseChannel ?: NpcActionChannel.MAIN_HAND
        activeItemUseChannel = null
        completeAction(NpcActionResult.succeeded("item use completed", actionId, channel))
    }

    /** Every accepted long-running action gets a terminal result when this entity leaves runtime. */
    private fun cancelActiveActionsForRemoval(state: NpcLifecycleState) {
        val detail = "action cancelled because NPC became ${state.name.lowercase()}"
        pendingNavigation = null
        navigationExpiresAt = Long.MIN_VALUE
        navigation.stop()
        val controlId = controlActionId
        if (controlId != null) {
            controlInput = NpcControlInput.IDLE
            controlActionId = null
            controlExpiresAt = Long.MIN_VALUE
            navigation.stop()
            completeAction(NpcActionResult.failed(detail, NpcActionCode.CANCELLED, controlId, NpcActionChannel.LOCOMOTION))
        }
        val rangedAction = activeRangedAttack
        if (rangedAction != null) {
            if (isUsingItem && usedItemHand == rangedAction.hand.toInteractionHand()) {
                stopUsingItem()
            }
            activeRangedAttack = null
            completeAction(NpcActionResult.failed(detail, NpcActionCode.CANCELLED, rangedAction.actionId, NpcActionChannel.COMBAT))
        }
        val itemActionId = activeItemUseActionId
        if (itemActionId != null) {
            val channel = activeItemUseChannel ?: NpcActionChannel.MAIN_HAND
            if (isUsingItem) {
                stopUsingItem()
            }
            activeItemUseActionId = null
            activeItemUseChannel = null
            completeAction(NpcActionResult.failed(detail, NpcActionCode.CANCELLED, itemActionId, channel))
        }
        val blockAction = activeBlockBreak
        if (blockAction != null) {
            clearBlockBreak(
                blockAction,
                NpcActionResult.failed(detail, NpcActionCode.CANCELLED, blockAction.actionId, NpcActionChannel.BLOCK_ACTION),
            )
        }
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
        )
    }

    private fun rangedAttackState(): NpcRangedAttackState? {
        val action = activeRangedAttack ?: return null
        return NpcRangedAttackState(
            targetUuid = action.targetUuid,
            hand = action.hand,
            weapon = action.weapon,
            phase = action.phase,
            elapsedTicks = rangedPhaseElapsedTicks(action),
            requiredChargeTicks = action.requiredChargeTicks,
        )
    }

    private fun blockBreakState(): NpcBlockBreakState? {
        val action = activeBlockBreak ?: return null
        return NpcBlockBreakState(
            position = NpcBlockPosition(action.position.x, action.position.y, action.position.z),
            progress = action.progress,
            stage = NpcBlockBreakMath.stage(action.progress),
            toolItemId = NpcItemClassifier.profile(mainHandItem).itemId,
        )
    }

    private fun advanceBlockBreak() {
        val action = activeBlockBreak ?: return
        val state = level().getBlockState(action.position)
        // A player-like strike is admitted only after a full eye-ray check in startBlockBreak.
        // Once admitted, small collision/nav settling movements must not cancel it because a
        // leaf or the trunk edge briefly crosses the eye ray. A one-tick physics settle can also
        // move its feet just beyond the strict start envelope. Keep the authoritative world,
        // tool and Forge-hook checks below, use a deliberately small active-only reach grace,
        // and do not re-run the transient LOS gate.
        val activeValidation = validateBlockBreak(
            action.position,
            state,
            requireLineOfSight = false,
            maxReachSqr = BLOCK_BREAK_ACTIVE_REACH_SQR,
        )
        if (activeValidation != null) {
            clearBlockBreak(
                action,
                NpcActionResult.failed(
                    "block break became invalid: ${activeValidation.detail}",
                    activeValidation.code,
                    action.actionId,
                    NpcActionChannel.BLOCK_ACTION,
                ),
            )
            return
        }
        val tool = mainHandItem
        val toolRejection = validateHeldMiningTool(state, tool)
        if (toolRejection != null) {
            clearBlockBreak(
                action,
                NpcActionResult.failed(toolRejection.detail, toolRejection.code, action.actionId, NpcActionChannel.BLOCK_ACTION),
            )
            return
        }
        val progress = NpcBlockBreakMath.progressPerTick(
            toolSpeed = miningToolSpeed(state, tool),
            hardness = state.getDestroySpeed(level(), action.position),
            canHarvest = NpcMiningSpeed.canHarvest(state.requiresCorrectToolForDrops(), tool.isCorrectToolForDrops(state)),
        )
        if (progress <= 0.0F) {
            clearBlockBreak(
                action,
                NpcActionResult.failed("held tool cannot make block-break progress", NpcActionCode.WORLD_REJECTED, action.actionId, NpcActionChannel.BLOCK_ACTION),
            )
            return
        }
        action.progress += progress
        if (level().gameTime % BREAK_SWING_INTERVAL == 0L) {
            startVisibleSwing()
        }
        if (action.progress < 1.0F) {
            publishBlockBreakProgress(action.position, NpcBlockBreakMath.stage(action.progress))
            return
        }
        completeBlockBreak(action, state, tool)
    }

    private fun completeBlockBreak(action: ActiveBlockBreak, state: BlockState, tool: ItemStack) {
        val serverLevel = level() as? ServerLevel
        val canHarvest = NpcMiningSpeed.canHarvest(state.requiresCorrectToolForDrops(), tool.isCorrectToolForDrops(state))
        if (serverLevel == null || !serverLevel.destroyBlock(action.position, canHarvest, this)) {
            clearBlockBreak(
                action,
                NpcActionResult.failed("world rejected block break", NpcActionCode.WORLD_REJECTED, action.actionId, NpcActionChannel.BLOCK_ACTION),
            )
            return
        }
        if (!tool.isEmpty && NpcToolDurability.perform(tool) { tool.item.mineBlock(tool, serverLevel, state, action.position, this) }) {
            // Item.mineBlock owns durability for vanilla tools. Calling hurtAndBreak here as well
            // double-damaged tools after every successful block break.
            refreshMainHandAttributes()
        }
        startVisibleSwing()
        clearBlockBreak(
            action,
            NpcActionResult.succeeded("block break completed", action.actionId, NpcActionChannel.BLOCK_ACTION),
        )
    }

    private fun validateBlockBreak(
        position: BlockPos,
        state: BlockState,
        requireLineOfSight: Boolean = true,
        maxReachSqr: Double = BLOCK_BREAK_REACH_SQR,
    ): NpcActionResult? {
        if (state.isAir) {
            return NpcActionResult.rejected("block is already air")
        }
        if (state.getDestroySpeed(level(), position) < 0.0F) {
            return NpcActionResult.rejected("block is unbreakable")
        }
        if (distanceToSqr(position.center) > maxReachSqr) {
            return NpcActionResult.rejected("block is out of break reach", NpcActionCode.OUT_OF_RANGE)
        }
        if (requireLineOfSight) {
            val sight = level().clip(
                ClipContext(
                    eyePosition,
                    position.center,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    this,
                ),
            )
            if (sight.type == HitResult.Type.BLOCK && sight.blockPos != position) {
                return NpcActionResult.rejected("block is not visible from the NPC eye position", NpcActionCode.WORLD_REJECTED)
            }
        }
        if (!ForgeHooks.canEntityDestroy(level(), position, this)) {
            return NpcActionResult.rejected("block break was denied by world rules or a Forge hook")
        }
        return null
    }

    /**
     * Select the fastest carried tool for this exact supplied block. This does not choose a task or
     * resource. Strict tool requirements remain the default; the two explicit configuration
     * exceptions permit ordinary hand mining without changing the caller's supplied target.
     */
    private fun prepareMiningTool(state: BlockState): NpcActionResult? {
        if (NpcSettingsConfig.enabled(NpcSetting.BARE_HANDS_ONLY)) return prepareEmptyMiningHand()
        val requiresCorrect = state.requiresCorrectToolForDrops()
        val requiresEffective = requiresEffectiveMiningTool(state)
        val candidates = inventory.indices.mapNotNull { slot ->
            val stack = inventory[slot]
            if (stack.isEmpty) {
                return@mapNotNull null
            }
            NpcMiningToolSelector.Candidate(
                slot = slot,
                destroySpeed = stack.getDestroySpeed(state),
                correctForDrops = stack.isCorrectToolForDrops(state),
                remainingDurability = if (stack.isDamageableItem) stack.maxDamage - stack.damageValue else Int.MAX_VALUE,
                currentlySelected = slot == selectedHotbarSlot,
            )
        }
        val chosen = NpcMiningToolSelector.choose(candidates, requiresCorrect, requiresEffective)
        if (chosen == null) {
            if (NpcSettingsConfig.enabled(NpcSetting.IGNORE_MISSING_TOOL)) return prepareEmptyMiningHand()
            return if (requiresEffective) {
                NpcActionResult.rejected(
                    "NPC carries no suitable tool for ${state.block.descriptionId}",
                    NpcActionCode.UNSUITABLE_TOOL,
                    NpcActionChannel.BLOCK_ACTION,
                )
            } else {
                null
            }
        }
        if (chosen.slot != selectedHotbarSlot) {
            val previous = inventory[selectedHotbarSlot]
            inventory[selectedHotbarSlot] = inventory[chosen.slot]
            inventory[chosen.slot] = previous
            refreshMainHandAttributes()
        }
        return validateHeldMiningTool(state, mainHandItem)
    }

    private fun validateHeldMiningTool(state: BlockState, tool: ItemStack): NpcActionResult? {
        val bareHands = NpcSettingsConfig.enabled(NpcSetting.BARE_HANDS_ONLY)
        if (tool.isEmpty && (bareHands || NpcSettingsConfig.enabled(NpcSetting.IGNORE_MISSING_TOOL))) return null
        if (bareHands) return NpcActionResult.rejected("bare-hands block work requires an empty selected hand", NpcActionCode.UNSUITABLE_TOOL)
        val requiresCorrect = state.requiresCorrectToolForDrops()
        if (requiresCorrect && (tool.isEmpty || !tool.isCorrectToolForDrops(state))) {
            return NpcActionResult.rejected(
                "held item is not the correct harvesting tool for ${state.block.descriptionId}",
                NpcActionCode.UNSUITABLE_TOOL,
                NpcActionChannel.BLOCK_ACTION,
            )
        }
        if (requiresEffectiveMiningTool(state) && (tool.isEmpty || tool.getDestroySpeed(state) <= NpcMiningToolSelector.HAND_DESTROY_SPEED)) {
            return NpcActionResult.rejected(
                "held item is not an effective mining tool for ${state.block.descriptionId}",
                NpcActionCode.UNSUITABLE_TOOL,
                NpcActionChannel.BLOCK_ACTION,
            )
        }
        return null
    }

    private fun requiresEffectiveMiningTool(state: BlockState): Boolean {
        // Foliage is intentionally breakable with the currently held item. Vanilla may tag it as
        // hoe-mineable, but a player with an axe may still clear a sight line without first
        // obtaining a hoe. This remains a mechanical rule for the exact caller-supplied block;
        // it does not choose foliage or navigation policy.
        if (state.`is`(BlockTags.LEAVES)) {
            return false
        }
        return state.requiresCorrectToolForDrops() ||
            state.`is`(BlockTags.MINEABLE_WITH_PICKAXE) ||
            state.`is`(BlockTags.MINEABLE_WITH_AXE) ||
            state.`is`(BlockTags.MINEABLE_WITH_SHOVEL) ||
            state.`is`(BlockTags.MINEABLE_WITH_HOE)
    }

    private fun miningToolSpeed(state: BlockState, tool: ItemStack): Float =
        NpcMiningSpeed.effectiveToolSpeed(
            baseToolSpeed = tool.getDestroySpeed(state),
            efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, tool),
            hasteAmplifier = getEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED)?.amplifier,
            fatigueAmplifier = getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN)?.amplifier,
            underwaterWithoutAquaAffinity = isEyeInFluid(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(this),
            airborne = !onGround(),
        )

    private fun resolveBlockContainer(position: NpcBlockPosition): Container? {
        val blockPos = BlockPos(position.x, position.y, position.z)
        if (distanceToSqr(blockPos.center) > BLOCK_INTERACTION_REACH_SQR) {
            return null
        }
        return NpcBlockContainers.resolve(level(), blockPos)
    }

    private fun publishBlockBreakProgress(position: BlockPos, stage: Int) {
        (level() as? ServerLevel)?.destroyBlockProgress(id, position, stage)
    }

    /** Keep the vanilla animate packet path, with tracked state as a late-client fallback. */
    private fun startVisibleSwing() {
        swing(InteractionHand.MAIN_HAND, true)
        if (!level().isClientSide) {
            val current = entityData.get(DATA_SWING_SEQUENCE)
            val next = if (current == Int.MAX_VALUE) 0 else current + 1
            entityData.set(DATA_SWING_SEQUENCE, next)
        }
    }

    private fun clearBlockBreak(action: ActiveBlockBreak, completion: NpcActionResult?) {
        publishBlockBreakProgress(action.position, -1)
        activeBlockBreak = null
        if (completion != null) {
            completeAction(completion)
        }
    }

    private fun attackStrengthScale(): Float {
        if (lastAttackGameTime == Long.MIN_VALUE) {
            return 1.0F
        }
        val elapsed = level().gameTime - lastAttackGameTime
        return NpcAttackTiming.strength(elapsed, getAttributeValue(Attributes.ATTACK_SPEED))
    }

    private fun prepareEmptyMiningHand(): NpcActionResult? {
        if (mainHandItem.isEmpty) return null
        val empty = inventory.indexOfFirst { it.isEmpty }
        if (empty < 0) return NpcActionResult.rejected("free one inventory slot before empty-hand block work; the held item must be preserved", NpcActionCode.MISSING_RESOURCE)
        inventory[empty] = inventory[selectedHotbarSlot]
        inventory[selectedHotbarSlot] = ItemStack.EMPTY
        refreshMainHandAttributes()
        return null
    }

    private fun damageMainHandAfterAttack(stack: ItemStack, target: LivingEntity) {
        NpcToolDurability.perform(stack) { damageMainHandNormally(stack, target) }
    }

    private fun damageMainHandNormally(stack: ItemStack, target: LivingEntity) {
        if (!stack.item.hurtEnemy(stack, target, this)) {
            return
        }
        stack.hurtAndBreak(1, this) { attacker -> attacker.broadcastBreakEvent(EquipmentSlot.MAINHAND) }
        refreshMainHandAttributes()
    }

    /**
     * BowItem.releaseUsing is hard-coupled to Player because it queries Player inventory. Core's
     * explicit ammunition reserve supplies that one missing resource while preserving arrow-item
     * projectile construction, charge, enchantments, durability and normal entity physics.
     */
    private fun releaseBow(bow: ItemStack, useTicks: Int, hand: InteractionHand): NpcActionResult {
        val ammo = findArrowAmmunition()
            ?: return NpcActionResult.rejected("bow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        val charge = BowItem.getPowerForTime(useTicks)
        if (charge < MIN_BOW_DRAW_POWER) {
            return NpcActionResult.rejected("bow draw is too short", NpcActionCode.NOT_READY, NpcActionChannel.COMBAT)
        }
        val arrowStack = ammo.stack
        val arrowItem = arrowStack.item as ArrowItem
        val projectile = arrowItem.createArrow(level(), arrowStack, this)
        projectile.shootFromRotation(this, xRot, yRot, 0.0F, charge * BOW_PROJECTILE_SPEED, BOW_INACCURACY)
        if (charge == 1.0F) {
            projectile.isCritArrow = true
        }
        val power = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, bow)
        if (power > 0) {
            projectile.baseDamage = projectile.baseDamage + power * POWER_DAMAGE_INCREMENT + POWER_DAMAGE_BASE_BONUS
        }
        val punch = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, bow)
        if (punch > 0) {
            projectile.knockback = punch
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FLAMING_ARROWS, bow) > 0) {
            projectile.setSecondsOnFire(ARROW_FIRE_SECONDS)
        }
        val infiniteArrow = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, bow) > 0 && arrowStack.`is`(Items.ARROW)
        if (infiniteArrow) {
            projectile.pickup = AbstractArrow.Pickup.CREATIVE_ONLY
        }
        if (!level().addFreshEntity(projectile)) {
            return NpcActionResult.failed("could not spawn bow projectile", NpcActionCode.WORLD_REJECTED, channel = NpcActionChannel.COMBAT)
        }
        if (!infiniteArrow) {
            consumeArrowAmmunition(ammo)
        }
        val equipmentSlot = if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND
        NpcToolDurability.perform(bow) { bow.hurtAndBreak(1, this) { attacker -> attacker.broadcastBreakEvent(equipmentSlot) } }
        if (hand == InteractionHand.MAIN_HAND) {
            refreshMainHandAttributes()
        }
        level().playSound(null, x, y, z, SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, BOW_SOUND_VOLUME, BOW_SOUND_PITCH_BASE / (random.nextFloat() * BOW_SOUND_PITCH_RANDOMNESS + BOW_SOUND_PITCH_OFFSET))
        return NpcActionResult.succeeded("fired bow using NPC-carried ammunition", channel = NpcActionChannel.COMBAT)
    }

    /** A release loads one reserve arrow; the next release fires CrossbowItem's charged NBT. */
    private fun releaseCrossbow(crossbow: ItemStack, useTicks: Int, hand: InteractionHand): NpcActionResult {
        if (CrossbowItem.isCharged(crossbow)) {
            NpcToolDurability.perform(crossbow) {
                CrossbowItem.performShooting(level(), this, hand, crossbow, CROSSBOW_PROJECTILE_SPEED, CROSSBOW_INACCURACY)
                crossbow.hurtAndBreak(1, this) { attacker -> attacker.broadcastBreakEvent(hand.equipmentSlot()) }
            }
            if (hand == InteractionHand.MAIN_HAND) {
                refreshMainHandAttributes()
            }
            return NpcActionResult.succeeded("fired charged crossbow")
        }
        val requiredCharge = crossbowChargeTicks(crossbow)
        if (useTicks < requiredCharge) {
            return NpcActionResult.rejected("crossbow charge is too short; needs $requiredCharge ticks")
        }
        val ammo = findArrowAmmunition()
            ?: return NpcActionResult.rejected("crossbow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        CrossbowItem.setCharged(crossbow, true)
        val projectiles = ListTag()
        projectiles.add(ammo.stack.copyWithCount(1).save(CompoundTag()))
        crossbow.orCreateTag.put(CROSSBOW_CHARGED_PROJECTILES_KEY, projectiles)
        consumeArrowAmmunition(ammo)
        level().playSound(null, x, y, z, SoundEvents.CROSSBOW_LOADING_END, SoundSource.NEUTRAL, CROSSBOW_SOUND_VOLUME, CROSSBOW_SOUND_PITCH)
        return NpcActionResult.succeeded("loaded crossbow from NPC-carried ammunition", channel = NpcActionChannel.COMBAT)
    }

    /** A normal trident is an ordinary projectile. Riptide stays explicit rather than faking Player travel. */
    private fun releaseTrident(trident: ItemStack, useTicks: Int, hand: InteractionHand): NpcActionResult {
        if (useTicks < MIN_TRIDENT_CHARGE_TICKS) {
            return NpcActionResult.rejected("trident charge is too short")
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, trident) > 0) {
            return NpcActionResult.unsupported("Riptide requires Player travel semantics and cannot be applied to a dedicated NPC")
        }
        val projectile = ThrownTrident(level(), this, trident.copy())
        projectile.shootFromRotation(this, xRot, yRot, 0.0F, TRIDENT_PROJECTILE_SPEED, TRIDENT_INACCURACY)
        if (!level().addFreshEntity(projectile)) {
            return NpcActionResult.failed("could not spawn trident projectile")
        }
        trident.shrink(1)
        if (hand == InteractionHand.MAIN_HAND) {
            refreshMainHandAttributes()
        }
        level().playSound(null, x, y, z, SoundEvents.TRIDENT_THROW, SoundSource.NEUTRAL, TRIDENT_SOUND_VOLUME, TRIDENT_SOUND_PITCH)
        return NpcActionResult.succeeded("threw trident")
    }

    private fun crossbowChargeTicks(crossbow: ItemStack): Int =
        (CROSSBOW_BASE_CHARGE_TICKS - EnchantmentHelper.getItemEnchantmentLevel(Enchantments.QUICK_CHARGE, crossbow) * CROSSBOW_QUICK_CHARGE_REDUCTION)
            .coerceAtLeast(CROSSBOW_MIN_CHARGE_TICKS)

    private fun findArrowAmmunition(): ArrowAmmunitionSource? {
        if (!ammunition.isEmpty && ammunition.item is ArrowItem) {
            return ArrowAmmunitionSource(ammunition, null)
        }
        for (slot in inventory.indices) {
            val stack = inventory[slot]
            if (!stack.isEmpty && stack.item is ArrowItem) {
                return ArrowAmmunitionSource(stack, slot)
            }
        }
        return null
    }

    private fun consumeArrowAmmunition(source: ArrowAmmunitionSource) {
        source.stack.shrink(1)
        val inventorySlot = source.inventorySlot
        if (inventorySlot == null) {
            if (source.stack.isEmpty) {
                ammunition = ItemStack.EMPTY
            }
            return
        }
        if (source.stack.isEmpty) {
            replaceInventoryStack(inventorySlot, ItemStack.EMPTY)
        } else if (inventorySlot == selectedHotbarSlot) {
            refreshMainHandAttributes()
        }
    }

    private fun insertIntoInventory(remaining: ItemStack): Int {
        val initialCount = remaining.count
        var selectedSlotTouched = false
        for (slot in inventory.indices) {
            val current = inventory[slot]
            if (!ItemStack.isSameItemSameTags(current, remaining) || current.count >= current.maxStackSize) {
                continue
            }
            val transfer = minOf(current.maxStackSize - current.count, remaining.count)
            current.grow(transfer)
            remaining.shrink(transfer)
            selectedSlotTouched = selectedSlotTouched || slot == selectedHotbarSlot
            if (remaining.isEmpty) {
                break
            }
        }
        if (!remaining.isEmpty) {
            for (slot in inventory.indices) {
                if (!inventory[slot].isEmpty) {
                    continue
                }
                val transfer = minOf(remaining.maxStackSize, remaining.count)
                val inserted = remaining.copy()
                inserted.count = transfer
                inventory[slot] = inserted
                remaining.shrink(transfer)
                selectedSlotTouched = selectedSlotTouched || slot == selectedHotbarSlot
                if (remaining.isEmpty) {
                    break
                }
            }
        }
        if (selectedSlotTouched) {
            refreshMainHandAttributes()
        }
        return initialCount - remaining.count
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

    private fun refreshMainHandAttributes() {
        val attributes = attributes
        attributes.removeAttributeModifiers(appliedMainHandStack.getAttributeModifiers(EquipmentSlot.MAINHAND))
        val current = mainHandItem
        attributes.addTransientAttributeModifiers(current.getAttributeModifiers(EquipmentSlot.MAINHAND))
        appliedMainHandStack = current.copy()
    }

    private fun resolveEntity(id: UUID): Entity? {
        val serverLevel = level() as? ServerLevel ?: return null
        return serverLevel.getEntity(id)
    }

    private fun completeAction(result: NpcActionResult) {
        val actionId = result.actionId ?: return
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

    private fun itemId(stack: ItemStack): String =
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
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
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
        val binding = summonerBinding
        skinBinding = if (binding != null && tag.contains(KEY_SKIN, CompoundTag.TAG_COMPOUND.toInt())) {
            SkinBinding.load(tag.getCompound(KEY_SKIN), binding.summonerUuid, binding.lastKnownName)
        } else if (binding != null) {
            SkinBinding.fallback(binding.summonerUuid, binding.lastKnownName)
        } else {
            null
        }
        ContainerHelper.loadAllItems(tag, inventory)
        ammunition = loadOptionalStack(tag, KEY_AMMUNITION)
        val persistedTotem = loadOptionalStack(tag, KEY_TOTEM)
        totem = persistedTotem.copyWithCount(persistedTotem.count.coerceAtMost(TOTEM_RESERVE_CAPACITY))
        selectedHotbarSlot = Mth.clamp(tag.getInt(KEY_SELECTED_SLOT), 0, HOTBAR_SIZE - 1)
        refreshMainHandAttributes()
        syncBinding()
        syncSkin()
        entityData.set(DATA_SELECTED_SLOT, selectedHotbarSlot.toByte())
    }

    companion object {
        private const val DATA_VERSION = 3
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
        private const val PICKUP_REACH_SQR = 2.0 * 2.0
        private const val PASSIVE_PICKUP_RADIUS = 1.0
        private const val MAX_PASSIVE_PICKUPS_PER_TICK = 8
        private const val PICKUP_SOUND_VOLUME = 0.2F
        private const val PICKUP_SOUND_VARIATION = 0.7F
        private const val PICKUP_SOUND_BASE_PITCH = 1.0F
        private const val PICKUP_SOUND_PITCH_MULTIPLIER = 2.0F
        private const val DROP_HEIGHT_OFFSET = 0.2
        private const val RANGED_REACH_SQR = 64.0 * 64.0
        private const val BOW_FULL_CHARGE_TICKS = 20
        private const val MIN_BOW_DRAW_POWER = 0.1F
        private const val BOW_PROJECTILE_SPEED = 3.0F
        private const val BOW_INACCURACY = 1.0F
        private const val POWER_DAMAGE_INCREMENT = 0.5
        private const val POWER_DAMAGE_BASE_BONUS = 0.5
        private const val ARROW_FIRE_SECONDS = 5
        private const val BOW_SOUND_VOLUME = 1.0F
        private const val BOW_SOUND_PITCH_BASE = 1.0F
        private const val BOW_SOUND_PITCH_RANDOMNESS = 0.4F
        private const val BOW_SOUND_PITCH_OFFSET = 1.2F
        private const val CROSSBOW_BASE_CHARGE_TICKS = 25
        private const val CROSSBOW_QUICK_CHARGE_REDUCTION = 5
        private const val CROSSBOW_MIN_CHARGE_TICKS = 5
        private const val CROSSBOW_PROJECTILE_SPEED = 3.15F
        private const val CROSSBOW_INACCURACY = 1.0F
        private const val CROSSBOW_SOUND_VOLUME = 1.0F
        private const val CROSSBOW_SOUND_PITCH = 1.0F
        private const val CROSSBOW_CHARGED_PROJECTILES_KEY = "ChargedProjectiles"
        private const val MIN_TRIDENT_CHARGE_TICKS = 10
        private const val TRIDENT_PROJECTILE_SPEED = 2.5F
        private const val TRIDENT_INACCURACY = 1.0F
        private const val TRIDENT_SOUND_VOLUME = 1.0F
        private const val TRIDENT_SOUND_PITCH = 1.0F
        private const val THROWN_POTION_SPEED = 0.5F
        private const val THROWN_POTION_INACCURACY = 1.0F
        private const val BLOCK_BREAK_REACH_SQR = 4.5 * 4.5
        // Only an already accepted break may tolerate this half-block post-physics settle.
        private const val BLOCK_BREAK_ACTIVE_REACH_SQR = 5.0 * 5.0
        private const val MAX_NAVIGATION_TARGET_DISTANCE_SQR = 64.0 * 64.0
        private const val MIN_NAVIGATION_SPEED_MULTIPLIER = 0.1F
        private const val MAX_NAVIGATION_SPEED_MULTIPLIER = 1.5F
        private const val NAVIGATION_REQUEST_TTL_TICKS = 200L
        private const val BLOCK_PLACE_REACH_SQR = 4.5 * 4.5
        private const val BLOCK_INTERACTION_REACH_SQR = 4.5 * 4.5
        private const val PLACEMENT_FACE_INSET = 0.001
        private const val ENTITY_INTERACTION_REACH_SQR = 4.5 * 4.5
        private const val ENTITY_HIT_TOLERANCE = 0.25
        private const val MAX_HIT_OFFSET_SQR = 1.5 * 1.5
        private const val BREAK_SWING_INTERVAL = 4L
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

    private data class ActiveBlockBreak(
            val actionId: UUID,
            val position: BlockPos,
            var progress: Float,
        )

        private data class PendingNavigation(
            val position: NpcPosition,
            val speedMultiplier: Float,
        )

        private data class ActiveRangedAttack(
            val actionId: UUID,
            val targetUuid: UUID,
            val hand: NpcHand,
            val weapon: NpcRangedWeaponKind,
            var phase: NpcRangedAttackPhase,
            var phaseStartedGameTime: Long,
            var requiredChargeTicks: Int,
        )

        private data class ArrowAmmunitionSource(
            val stack: ItemStack,
            val inventorySlot: Int?,
        )

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
