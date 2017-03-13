package fi.dy.masa.itemscroller.event;

import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import net.minecraft.client.gui.GuiMerchant;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotMerchantResult;
import net.minecraft.item.ItemStack;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.SlotItemHandler;
import fi.dy.masa.itemscroller.ItemScroller;
import fi.dy.masa.itemscroller.config.Configs;
import fi.dy.masa.itemscroller.config.Configs.SlotRange;
import fi.dy.masa.itemscroller.proxy.ClientProxy;
import fi.dy.masa.itemscroller.util.ItemType;
import fi.dy.masa.itemscroller.util.MethodHandleUtils;

@SideOnly(Side.CLIENT)
public class InputEventHandler
{
    private boolean disabled;
    private int lastPosX;
    private int lastPosY;
    private int slotNumberLast;
    private final Set<Integer> draggedSlots = new HashSet<Integer>();
    private WeakReference<Slot> sourceSlotCandidate = new WeakReference<Slot>(null);
    private WeakReference<Slot> sourceSlot = new WeakReference<Slot>(null);
    private ItemStack stackInCursorLast = EMPTY_STACK;
    private ItemStack[] craftingRecipe = new ItemStack[9];
    private ItemStack craftingResult = EMPTY_STACK;
    private final Field fieldGuiLeft;
    private final Field fieldGuiTop;
    private final Field fieldGuiXSize;
    private final Field fieldGuiYSize;
    private final Field fieldSelectedMerchantRecipe;
    private final MethodHandle methodHandle_getSlotAtPosition;

    public InputEventHandler()
    {
        this.fieldGuiLeft =  ReflectionHelper.findField(GuiContainer.class, "field_147003_i", "guiLeft");
        this.fieldGuiTop =   ReflectionHelper.findField(GuiContainer.class, "field_147009_r", "guiTop");
        this.fieldGuiXSize = ReflectionHelper.findField(GuiContainer.class, "field_146999_f", "xSize");
        this.fieldGuiYSize = ReflectionHelper.findField(GuiContainer.class, "field_147000_g", "ySize");
        this.fieldSelectedMerchantRecipe = ReflectionHelper.findField(GuiMerchant.class, "field_147041_z", "selectedMerchantRecipe");
        this.methodHandle_getSlotAtPosition = MethodHandleUtils.getMethodHandleVirtual(GuiContainer.class,
                new String[] { "func_146975_c", "getSlotAtPosition" }, int.class, int.class);

        this.ensureRecipeSizeAndClearRecipe(9);
    }

    @SubscribeEvent
    public void onMouseInputEventPre(GuiScreenEvent.MouseInputEvent.Pre event)
    {
        if (this.disabled == false && event.getGui() instanceof GuiContainer &&
            (event.getGui() instanceof GuiContainerCreative) == false &&
            event.getGui().mc != null && event.getGui().mc.player != null &&
            Configs.GUI_BLACKLIST.contains(event.getGui().getClass().getName()) == false)
        {
            GuiContainer gui = (GuiContainer) event.getGui();
            int dWheel = Mouse.getEventDWheel();
            boolean cancel = false;

            if (dWheel != 0)
            {
                cancel = this.tryMoveItems(gui, dWheel > 0);
            }
            else
            {
                this.checkForItemPickup(gui);
                this.storeSourceSlotCandidate(gui);

                if (Configs.enableShiftPlaceItems && this.canShiftPlaceItems(gui))
                {
                    cancel = this.shiftPlaceItems(gui);
                }
                else if (Configs.enableShiftDropItems && this.canShiftDropItems(gui))
                {
                    cancel = this.shiftDropItems(gui);
                }
                else if (Configs.enableDragMovingShiftLeft || Configs.enableDragMovingShiftRight || Configs.enableDragMovingControlLeft)
                {
                    cancel = this.dragMoveItems(gui);
                }
            }

            if (cancel)
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onKeyInputEventPre(GuiScreenEvent.KeyboardInputEvent.Pre event)
    {
        if ((event.getGui() instanceof GuiContainer) == false ||
            event.getGui().mc == null || event.getGui().mc.player == null)
        {
            return;
        }

        GuiContainer gui = (GuiContainer) event.getGui();

        if (Keyboard.getEventKey() == Keyboard.KEY_I && Keyboard.getEventKeyState() &&
            GuiScreen.isAltKeyDown() && GuiScreen.isCtrlKeyDown() && GuiScreen.isShiftKeyDown())
        {
            if (gui.getSlotUnderMouse() != null)
            {
                debugPrintSlotInfo(gui, gui.getSlotUnderMouse());
            }
            else
            {
                ItemScroller.logger.info("GUI class: {}", gui.getClass().getName());
            }
        }
        // Drop all matching stacks from the same inventory when pressing Ctrl + Shift + Drop key
        else if (Configs.enableControlShiftDropkeyDropItems && Keyboard.getEventKeyState() &&
            Configs.GUI_BLACKLIST.contains(gui.getClass().getName()) == false &&
            GuiScreen.isCtrlKeyDown() && GuiScreen.isShiftKeyDown() &&
            gui.mc.gameSettings.keyBindDrop.isActiveAndMatches(Keyboard.getEventKey()))
        {
            Slot slot = gui.getSlotUnderMouse();

            if (slot != null && slot.getHasStack())
            {
                this.dropStacks(gui, slot.getStack(), slot);
            }
        }
        else if (Keyboard.getEventKeyState() && ClientProxy.KEY_DISABLE.isActiveAndMatches(Keyboard.getEventKey()))
        {
            this.disabled = ! this.disabled;

            if (this.disabled)
            {
                gui.mc.player.playSound(SoundEvents.BLOCK_NOTE_BASS, 0.8f, 0.8f);
            }
            else
            {
                gui.mc.player.playSound(SoundEvents.BLOCK_NOTE_PLING, 0.5f, 1.0f);
            }
        }
    }

    /**
     * Store a reference to the slot when a slot is left or right clicked on.
     * The slot is then later used to determine which inventory an ItemStack was
     * picked up from, if the stack from the cursor is dropped while holding shift.
     */
    private void storeSourceSlotCandidate(GuiContainer gui)
    {
        // Left or right mouse button was pressed
        if (Mouse.getEventButtonState() && (Mouse.getEventButton() == 0 || Mouse.getEventButton() == 1))
        {
            Slot slot = gui.getSlotUnderMouse();

            if (slot != null)
            {
                ItemStack stackCursor = gui.mc.player.inventory.getItemStack();
                ItemStack stack = EMPTY_STACK;

                if (isStackEmpty(stackCursor) == false)
                {
                    // Do a cheap copy without NBT data
                    stack = new ItemStack(stackCursor.getItem(), getStackSize(stackCursor), stackCursor.getMetadata());
                }

                this.stackInCursorLast = stack;
                this.sourceSlotCandidate = new WeakReference<Slot>(slot);
            }
        }
    }

    /**
     * Check if the (previous) mouse event resulted in picking up a new ItemStack to the cursor
     */
    private void checkForItemPickup(GuiContainer gui)
    {
        ItemStack stackCursor = gui.mc.player.inventory.getItemStack();

        // Picked up or swapped items to the cursor, grab a reference to the slot that the items came from
        // Note that we are only checking the item and metadata here!
        if (isStackEmpty(stackCursor) == false && stackCursor.isItemEqual(this.stackInCursorLast) == false)
        {
            this.sourceSlot = new WeakReference<Slot>(this.sourceSlotCandidate.get());
        }
    }

    private static void debugPrintSlotInfo(GuiContainer gui, Slot slot)
    {
        if (slot == null)
        {
            ItemScroller.logger.info("slot was null");
            return;
        }

        boolean hasSlot = gui.inventorySlots.inventorySlots.contains(slot);
        Object inv = slot instanceof SlotItemHandler ? ((SlotItemHandler) slot).getItemHandler() : slot.inventory;
        String stackStr = getStackString(slot.getStack());

        ItemScroller.logger.info(String.format("slot: slotNumber: %d, getSlotIndex(): %d, getHasStack(): %s, " +
                "slot class: %s, inv class: %s, Container's slot list has slot: %s, stack: %s",
                slot.slotNumber, slot.getSlotIndex(), slot.getHasStack(), slot.getClass().getName(),
                inv != null ? inv.getClass().getName() : "<null>", hasSlot ? " true" : "false", stackStr));
    }

    private static String getStackString(ItemStack stack)
    {
        if (isStackEmpty(stack) == false)
        {
            return String.format("[%s @ %d - display: %s - NBT: %s] (%s)",
                    stack.getItem().getRegistryName(), stack.getMetadata(), stack.getDisplayName(),
                    stack.getTagCompound() != null ? stack.getTagCompound().toString() : "<no NBT>",
                    stack.toString());
        }

        return "<empty>";
    }

    private boolean isValidSlot(Slot slot, GuiContainer gui, boolean requireItems)
    {
        return gui.inventorySlots != null && gui.inventorySlots.inventorySlots != null &&
                slot != null && gui.inventorySlots.inventorySlots.contains(slot) &&
                (requireItems == false || slot.getHasStack()) &&
                Configs.SLOT_BLACKLIST.contains(slot.getClass().getName()) == false;
    }

    private boolean isCraftingSlot(GuiContainer gui, Slot slot)
    {
        return slot != null && Configs.getCraftingGridSlots(gui, slot) != null;
    }

    /**
     * Checks if there are slots belonging to another inventory on screen above the given slot
     */
    private boolean inventoryExistsAbove(Slot slot, Container container)
    {
        for (Slot slotTmp : container.inventorySlots)
        {
            if (slotTmp.yPos < slot.yPos && areSlotsInSameInventory(slot, slotTmp) == false)
            {
                return true;
            }
        }

        return false;
    }

    private boolean canShiftPlaceItems(GuiContainer gui)
    {
        if (GuiScreen.isShiftKeyDown() == false || Mouse.getEventButton() != 0)
        {
            return false;
        }

        Slot slot = gui.getSlotUnderMouse();
        ItemStack stackCursor = gui.mc.player.inventory.getItemStack();

        // The target slot needs to be an empty, valid slot, and there needs to be items in the cursor
        return slot != null && isStackEmpty(stackCursor) == false && this.isValidSlot(slot, gui, false) &&
               slot.getHasStack() == false && slot.isItemValid(stackCursor);
    }

    private boolean shiftPlaceItems(GuiContainer gui)
    {
        Slot slot = gui.getSlotUnderMouse();

        // Left click to place the items from the cursor to the slot
        this.leftClickSlot(gui, slot.slotNumber);

        // Ugly fix to prevent accidentally drag-moving the stack from the slot that it was just placed into...
        this.draggedSlots.add(slot.slotNumber);

        this.tryMoveStacks(slot, gui, true, false, false);

        return true;
    }

    private boolean canShiftDropItems(GuiContainer gui)
    {
        if (GuiScreen.isShiftKeyDown() == false || Mouse.getEventButton() != 0 ||
            isStackEmpty(gui.mc.player.inventory.getItemStack()))
        {
            return false;
        }

        try
        {
            int left = this.fieldGuiLeft.getInt(gui);
            int top = this.fieldGuiTop.getInt(gui);
            int xSize = this.fieldGuiXSize.getInt(gui);
            int ySize = this.fieldGuiYSize.getInt(gui);
            int mouseAbsX = Mouse.getEventX() * gui.width / gui.mc.displayWidth;
            int mouseAbsY = gui.height - Mouse.getEventY() * gui.height / gui.mc.displayHeight - 1;
            boolean isOutsideGui = mouseAbsX < left || mouseAbsY < top || mouseAbsX >= left + xSize || mouseAbsY >= top + ySize;

            return isOutsideGui && this.getSlotAtPosition(gui, mouseAbsX - left, mouseAbsY - top) == null;
        }
        catch (IllegalAccessException e)
        {
            ItemScroller.logger.warn("Failed to reflect GuiContainer#guiLeft or guiTop or xSize or ySize");
        }

        return false;
    }

    private boolean shiftDropItems(GuiContainer gui)
    {
        ItemStack stackReference = gui.mc.player.inventory.getItemStack();

        if (isStackEmpty(stackReference) == false)
        {
            stackReference = stackReference.copy();

            // First drop the existing stack from the cursor
            this.dropItemsFromCursor(gui);

            this.dropStacks(gui, stackReference, this.sourceSlot.get());
            return true;
        }

        return false;
    }

    private void dropStacks(GuiContainer gui, ItemStack stackReference, Slot sourceInvSlot)
    {
        if (sourceInvSlot != null && isStackEmpty(stackReference) == false)
        {
            Container container = gui.inventorySlots;
            stackReference = stackReference.copy();

            for (Slot slot : container.inventorySlots)
            {
                // If this slot is in the same inventory that the items were picked up to the cursor from
                // and the stack is identical to the one in the cursor, then this stack will get dropped.
                if (areSlotsInSameInventory(slot, sourceInvSlot) && areStacksEqual(slot.getStack(), stackReference))
                {
                    // Drop the stack
                    this.dropStack(gui, slot.slotNumber);
                }
            }
        }
    }

    private boolean dragMoveItems(GuiContainer gui)
    {
        int mouseX = Mouse.getEventX() * gui.width / gui.mc.displayWidth;
        int mouseY = gui.height - Mouse.getEventY() * gui.height / gui.mc.displayHeight - 1;

        if (isStackEmpty(gui.mc.player.inventory.getItemStack()) == false)
        {
            // Updating these here is part of the fix to preventing a drag after shift + place
            this.lastPosX = mouseX;
            this.lastPosY = mouseY;
            return false;
        }

        boolean eventKeyIsLeftButton = Mouse.getEventButton() == 0;
        boolean eventKeyIsRightButton = Mouse.getEventButton() == 1;
        boolean leftButtonDown = Mouse.isButtonDown(0);
        boolean rightButtonDown = Mouse.isButtonDown(1);
        boolean isShiftDown = GuiScreen.isShiftKeyDown();
        boolean isControlDown = GuiScreen.isCtrlKeyDown();
        boolean eitherMouseButtonDown = leftButtonDown || rightButtonDown;

        if ((isShiftDown && leftButtonDown && Configs.enableDragMovingShiftLeft == false) ||
            (isShiftDown && rightButtonDown && Configs.enableDragMovingShiftRight == false) ||
            (isControlDown && eitherMouseButtonDown && Configs.enableDragMovingControlLeft == false))
        {
            return false;
        }

        boolean leaveOneItem = leftButtonDown == false;
        boolean moveOnlyOne = isShiftDown == false;
        boolean cancel = false;

        if (Mouse.getEventButtonState())
        {
            if (((eventKeyIsLeftButton || eventKeyIsRightButton) && isControlDown && Configs.enableDragMovingControlLeft) ||
                (eventKeyIsRightButton && isShiftDown && Configs.enableDragMovingShiftRight))
            {
                // Reset this or the method call won't do anything...
                this.slotNumberLast = -1;
                cancel = this.dragMoveFromSlotAtPosition(gui, mouseX, mouseY, leaveOneItem, moveOnlyOne);
            }
        }

        // Check that either mouse button is down
        if (cancel == false && (isShiftDown || isControlDown) && eitherMouseButtonDown)
        {
            int distX = mouseX - this.lastPosX;
            int distY = mouseY - this.lastPosY;
            int absX = Math.abs(distX);
            int absY = Math.abs(distY);

            if (absX > absY)
            {
                int inc = distX > 0 ? 1 : -1;

                for (int x = this.lastPosX; ; x += inc)
                {
                    int y = absX != 0 ? this.lastPosY + ((x - this.lastPosX) * distY / absX) : mouseY;
                    this.dragMoveFromSlotAtPosition(gui, x, y, leaveOneItem, moveOnlyOne);

                    if (x == mouseX)
                    {
                        break;
                    }
                }
            }
            else
            {
                int inc = distY > 0 ? 1 : -1;

                for (int y = this.lastPosY; ; y += inc)
                {
                    int x = absY != 0 ? this.lastPosX + ((y - this.lastPosY) * distX / absY) : mouseX;
                    this.dragMoveFromSlotAtPosition(gui, x, y, leaveOneItem, moveOnlyOne);

                    if (y == mouseY)
                    {
                        break;
                    }
                }
            }
        }

        this.lastPosX = mouseX;
        this.lastPosY = mouseY;

        // Always update the slot under the mouse.
        // This should prevent a "double click/move" when shift + left clicking on slots that have more
        // than one stack of items. (the regular slotClick() + a "drag move" from the slot that is under the mouse
        // when the left mouse button is pressed down and this code runs).
        Slot slot = this.getSlotAtPosition(gui, mouseX, mouseY);
        this.slotNumberLast = slot != null ? slot.slotNumber : -1;

        if (eitherMouseButtonDown == false)
        {
            this.draggedSlots.clear();
        }

        return cancel;
    }

    private boolean dragMoveFromSlotAtPosition(GuiContainer gui, int x, int y, boolean leaveOneItem, boolean moveOnlyOne)
    {
        Slot slot = this.getSlotAtPosition(gui, x, y);
        boolean flag = slot != null && this.isValidSlot(slot, gui, true) && slot.canTakeStack(gui.mc.player);
        boolean cancel = flag && (leaveOneItem || moveOnlyOne);

        if (flag && slot.slotNumber != this.slotNumberLast && this.draggedSlots.contains(slot.slotNumber) == false)
        {
            if (moveOnlyOne)
            {
                cancel = this.tryMoveSingleItemToOtherInventory(slot, gui);
            }
            else if (leaveOneItem)
            {
                cancel = this.tryMoveAllButOneItemToOtherInventory(slot, gui);
            }
            else
            {
                this.shiftClickSlot(gui, slot.slotNumber);
                cancel = true;
            }

            this.draggedSlots.add(slot.slotNumber);
        }

        return cancel;
    }

    private Slot getSlotAtPosition(GuiContainer gui, int x, int y)
    {
        try
        {
            return (Slot) this.methodHandle_getSlotAtPosition.invokeExact(gui, x, y);
        }
        catch (Throwable e)
        {
            ItemScroller.logger.error("Error while trying invoke GuiContainer#getSlotAtPosition() from {}", gui.getClass().getSimpleName(), e);
        }

        return null;
    }

    private boolean tryMoveItemsVillager(GuiMerchant gui, Slot slot, boolean moveToOtherInventory, boolean isShiftDown)
    {
        if (isShiftDown)
        {
            // Try to fill the merchant's buy slots from the player inventory
            if (moveToOtherInventory == false)
            {
                this.tryMoveItemsToMerchantBuySlots(gui, true);
            }
            // Move items from sell slot to player inventory
            else if (slot.getHasStack())
            {
                this.tryMoveStacks(slot, gui, true, true, true);
            }
            // Scrolling over an empty output slot, clear the buy slots
            else
            {
                this.tryMoveStacks(slot, gui, false, true, false);
            }
        }
        else
        {
            // Scrolling items from player inventory into merchant buy slots
            if (moveToOtherInventory == false)
            {
                this.tryMoveItemsToMerchantBuySlots(gui, false);
            }
            // Scrolling items from this slot/inventory into the other inventory
            else if (slot.getHasStack())
            {
                this.moveOneSetOfItemsFromSlotToOtherInventory(gui, slot);
            }
        }

        return false;
    }

    private boolean tryMoveItemsCrafting(GuiContainer gui, Slot slot, boolean moveToOtherInventory, boolean isShiftDown)
    {
        if (isShiftDown)
        {
            // Try to fill the crafting grid
            if (moveToOtherInventory == false)
            {
                /*if (slot.getHasStack())
                {
                    this.storeCraftingRecipe(gui, slot);
                }*/

                if (isStackEmpty(this.craftingResult) == false)
                {
                    this.tryMoveItemsToCraftingGridSlots(gui, slot, true);
                }
            }
            // Move items from the crafting output slot
            else if (slot.getHasStack())
            {
                this.storeCraftingRecipe(gui, slot);
                this.shiftClickSlot(gui, slot.slotNumber);
            }
            // Scrolling over an empty crafting output slot, clear the crafting grid
            else
            {
                SlotRange range = Configs.getCraftingGridSlots(gui, slot);

                if (range != null)
                {
                    for (int i = 0, s = range.getFirst(); i < range.getSlotCount(); i++, s++)
                    {
                        this.shiftClickSlot(gui, s);
                    }
                }
            }
        }
        else
        {
            // Scrolling items from player inventory into crafting grid slots
            if (moveToOtherInventory == false)
            {
                /*if (slot.getHasStack())
                {
                    this.storeCraftingRecipe(gui, slot);
                }*/

                if (isStackEmpty(this.craftingResult) == false)
                {
                    this.tryMoveItemsToCraftingGridSlots(gui, slot, false);
                }
            }
            // Scrolling items from this crafting slot into the other inventory
            else if (slot.getHasStack())
            {
                this.storeCraftingRecipe(gui, slot);
                this.moveOneSetOfItemsFromSlotToOtherInventory(gui, slot);
            }
            // Scrolling over an empty crafting output slot, clear the crafting grid
            else
            {
                SlotRange range = Configs.getCraftingGridSlots(gui, slot);

                if (range != null)
                {
                    for (int i = 0, s = range.getFirst(); i < range.getSlotCount(); i++, s++)
                    {
                        this.shiftClickSlot(gui, s);
                    }
                }
            }
        }

        return false;
    }

    private void ensureRecipeSizeAndClearRecipe(int size)
    {
        if (this.craftingRecipe.length != size)
        {
            this.craftingRecipe = new ItemStack[size];
        }

        for (int i = 0; i < size; i++)
        {
            this.craftingRecipe[i] = EMPTY_STACK;
        }
    }

    private void storeCraftingRecipe(GuiContainer gui, Slot slot)
    {
        SlotRange range = Configs.getCraftingGridSlots(gui, slot);

        if (range != null && slot.getHasStack())
        {

            if (areStacksEqual(this.craftingResult, slot.getStack()) == false ||
                this.craftingRecipe.length != range.getSlotCount())
            {
                int gridSize = range.getSlotCount();
                this.ensureRecipeSizeAndClearRecipe(gridSize);
                this.craftingResult = slot.getStack().copy();
                int numSlots = gui.inventorySlots.inventorySlots.size();

                for (int i = 0, s = range.getFirst(); i < gridSize && s < numSlots; i++, s++)
                {
                    Slot slotTmp = gui.inventorySlots.getSlot(s);
                    this.craftingRecipe[i] = slotTmp.getHasStack() ? slotTmp.getStack().copy() : EMPTY_STACK;
                }
            }
        }
    }

    private boolean tryMoveItems(GuiContainer gui, boolean scrollingUp)
    {
        Slot slot = gui.getSlotUnderMouse();

        // We require an empty cursor
        if (slot == null || isStackEmpty(gui.mc.player.inventory.getItemStack()) == false)
        {
            return false;
        }

        // Villager handling only happens when scrolling over the trade output slot
        boolean villagerHandling = Configs.enableScrollingVillager && gui instanceof GuiMerchant && slot instanceof SlotMerchantResult;
        boolean craftingSlot = this.isCraftingSlot(gui, slot);

        // Check that the slot is valid, (don't require items in case of the villager output slot or a crafting slot)
        if (this.isValidSlot(slot, gui, villagerHandling || craftingSlot ? false : true) == false)
        {
            return false;
        }

        boolean isCtrlDown = GuiContainer.isCtrlKeyDown();
        boolean isShiftDown = GuiContainer.isShiftKeyDown();
        boolean moveToOtherInventory = scrollingUp;

        if (Configs.useSlotPositionAwareScrollDirection)
        {
            boolean above = this.inventoryExistsAbove(slot, gui.inventorySlots);
            // so basically: (above && scrollingUp) || (above == false && scrollingUp == false)
            moveToOtherInventory = above == scrollingUp;
        }

        if ((Configs.reverseScrollDirectionSingle && isShiftDown == false) ||
            (Configs.reverseScrollDirectionStacks && isShiftDown))
        {
            moveToOtherInventory = ! moveToOtherInventory;
        }

        if (craftingSlot)
        {
            return this.tryMoveItemsCrafting(gui, slot, moveToOtherInventory, isShiftDown);
        }

        if (villagerHandling)
        {
            return this.tryMoveItemsVillager((GuiMerchant) gui, slot, moveToOtherInventory, isShiftDown);
        }

        if ((Configs.enableScrollingSingle == false && isShiftDown == false && isCtrlDown == false) ||
            (Configs.enableScrollingStacks == false && isShiftDown && isCtrlDown == false) ||
            (Configs.enableScrollingMatchingStacks == false && isShiftDown == false && isCtrlDown) ||
            (Configs.enableScrollingEverything == false && isShiftDown && isCtrlDown))
        {
            return false;
        }

        if (isShiftDown)
        {
            // Ctrl + Shift + scroll: move everything
            if (isCtrlDown)
            {
                this.tryMoveStacks(slot, gui, false, moveToOtherInventory, false);
            }
            // Shift + scroll: move one matching stack
            else
            {
                this.tryMoveStacks(slot, gui, true, moveToOtherInventory, true);
            }
            return true;
        }
        // Ctrl + scroll: Move all matching stacks
        else if (isCtrlDown)
        {
            this.tryMoveStacks(slot, gui, true, moveToOtherInventory, false);
            return true;
        }
        // No Ctrl or Shift
        else
        {
            ItemStack stack = slot.getStack();

            // Scrolling items from this slot/inventory into the other inventory
            if (moveToOtherInventory)
            {
                return this.tryMoveSingleItemToOtherInventory(slot, gui);
            }
            // Scrolling items from the other inventory into this slot/inventory
            else if (getStackSize(stack) < slot.getItemStackLimit(stack))
            {
                return this.tryMoveSingleItemToThisInventory(slot, gui);
            }
        }

        return false;
    }

    private boolean tryMoveSingleItemToOtherInventory(Slot slot, GuiContainer gui)
    {
        ItemStack stackOrig = slot.getStack();
        Container container = gui.inventorySlots;

        if (isStackEmpty(gui.mc.player.inventory.getItemStack()) == false || slot.canTakeStack(gui.mc.player) == false ||
            (getStackSize(stackOrig) > 1 && slot.isItemValid(stackOrig) == false))
        {
            return false;
        }

        // Can take all the items to the cursor at once, use a shift-click method to move one item from the slot
        if (getStackSize(stackOrig) <= stackOrig.getMaxStackSize())
        {
            return this.clickSlotsToMoveSingleItemByShiftClick(gui, slot.slotNumber);
        }

        ItemStack stack = stackOrig.copy();
        setStackSize(stack, 1);

        ItemStack[] originalStacks = this.getOriginalStacks(container);

        // Try to move the temporary single-item stack via the shift-click handler method
        slot.putStack(stack);
        container.transferStackInSlot(gui.mc.player, slot.slotNumber);

        // Successfully moved the item somewhere, now we want to check where it went
        if (slot.getHasStack() == false)
        {
            int targetSlot = this.getTargetSlot(container, originalStacks);

            // Found where the item went
            if (targetSlot >= 0)
            {
                // Remove the dummy item from the target slot (on the client side)
                container.inventorySlots.get(targetSlot).decrStackSize(1);

                // Restore the original stack to the slot under the cursor (on the client side)
                this.restoreOriginalStacks(container, originalStacks);

                // Do the slot clicks to actually move the items (on the server side)
                return this.clickSlotsToMoveSingleItem(gui, slot.slotNumber, targetSlot);
            }
        }

        // Restore the original stack to the slot under the cursor (on the client side)
        slot.putStack(stackOrig);

        return false;
    }

    private boolean tryMoveAllButOneItemToOtherInventory(Slot slot, GuiContainer gui)
    {
        EntityPlayer player = gui.mc.player;
        ItemStack stackOrig = slot.getStack().copy();

        if (getStackSize(stackOrig) == 1 || getStackSize(stackOrig) > stackOrig.getMaxStackSize() ||
            slot.canTakeStack(player) == false || slot.isItemValid(stackOrig) == false)
        {
            return true;
        }

        // Take half of the items from the original slot to the cursor
        this.rightClickSlot(gui, slot.slotNumber);

        ItemStack stackInCursor = player.inventory.getItemStack();
        if (isStackEmpty(stackInCursor))
        {
            return false;
        }

        int stackInCursorSizeOrig = getStackSize(stackInCursor);
        int tempSlotNum = -1;

        // Find some other slot where to store one of the items temporarily
        for (Slot slotTmp : gui.inventorySlots.inventorySlots)
        {
            if (slotTmp.slotNumber != slot.slotNumber && slotTmp.isItemValid(stackInCursor))
            {
                ItemStack stackInSlot = slotTmp.getStack();

                if (isStackEmpty(stackInSlot) || areStacksEqual(stackInSlot, stackInCursor))
                {
                    // Try to put one item into the temporary slot
                    this.rightClickSlot(gui, slotTmp.slotNumber);

                    stackInCursor = player.inventory.getItemStack();

                    // Successfully stored one item
                    if (isStackEmpty(stackInCursor) || getStackSize(stackInCursor) < stackInCursorSizeOrig)
                    {
                        tempSlotNum = slotTmp.slotNumber;
                        break;
                    }
                }
            }
        }

        // Return the rest of the items into the original slot
        this.leftClickSlot(gui, slot.slotNumber);

        // Successfully stored one item in a temporary slot
        if (tempSlotNum != -1)
        {
            // Shift click the stack from the original slot
            this.shiftClickSlot(gui, slot.slotNumber);

            // Take half a stack from the temporary slot
            this.rightClickSlot(gui, tempSlotNum);

            // Return one item into the original slot
            this.rightClickSlot(gui, slot.slotNumber);

            // Return the rest of the items to the temporary slot, if any
            if (isStackEmpty(player.inventory.getItemStack()) == false)
            {
                this.leftClickSlot(gui, tempSlotNum);
            }

            return true;
        }

        return false;
    }

    private boolean tryMoveSingleItemToThisInventory(Slot slot, GuiContainer gui)
    {
        Container container = gui.inventorySlots;
        ItemStack stackOrig = slot.getStack();

        if (slot.isItemValid(stackOrig) == false)
        {
            return false;
        }

        for (int slotNum = container.inventorySlots.size() - 1; slotNum >= 0; slotNum--)
        {
            Slot slotTmp = container.inventorySlots.get(slotNum);
            ItemStack stackTmp = slotTmp.getStack();

            if (areSlotsInSameInventory(slotTmp, slot) == false &&
                isStackEmpty(stackTmp) == false && slotTmp.canTakeStack(gui.mc.player) &&
                (getStackSize(stackTmp) == 1 || slotTmp.isItemValid(stackTmp)))
            {
                if (areStacksEqual(stackTmp, stackOrig))
                {
                    return this.clickSlotsToMoveSingleItem(gui, slotTmp.slotNumber, slot.slotNumber);
                }
            }
        }

        // If we weren't able to move any items from another inventory, then try to move items
        // within the same inventory (mostly between the hotbar and the player inventory)
        /*
        for (Slot slotTmp : container.inventorySlots)
        {
            ItemStack stackTmp = slotTmp.getStack();

            if (slotTmp.slotNumber != slot.slotNumber &&
                isStackEmpty(stackTmp) == false && slotTmp.canTakeStack(gui.mc.player) &&
                (getStackSize(stackTmp) == 1 || slotTmp.isItemValid(stackTmp)))
            {
                if (areStacksEqual(stackTmp, stackOrig))
                {
                    return this.clickSlotsToMoveSingleItem(gui, slotTmp.slotNumber, slot.slotNumber);
                }
            }
        }
        */

        return false;
    }

    private void tryMoveStacks(Slot slot, GuiContainer gui, boolean matchingOnly, boolean toOtherInventory, boolean firstOnly)
    {
        Container container = gui.inventorySlots;
        ItemStack stackReference = slot.getStack();

        for (Slot slotTmp : container.inventorySlots)
        {
            if (slotTmp.slotNumber != slot.slotNumber &&
                areSlotsInSameInventory(slotTmp, slot) == toOtherInventory && slotTmp.getHasStack() &&
                (matchingOnly == false || areStacksEqual(stackReference, slotTmp.getStack())))
            {
                boolean success = this.shiftClickSlotWithCheck(gui, slotTmp.slotNumber);

                // Failed to shift-click items, try a manual method
                if (success == false && Configs.enableScrollingStacksFallback)
                {
                    this.clickSlotsToMoveItemsFromSlot(slotTmp, gui, toOtherInventory);
                }

                if (firstOnly)
                {
                    return;
                }
            }
        }

        // If moving to the other inventory, then move the hovered slot's stack last
        if (toOtherInventory && this.shiftClickSlotWithCheck(gui, slot.slotNumber) == false)
        {
            this.clickSlotsToMoveItemsFromSlot(slot, gui, toOtherInventory);
        }
    }

    private void tryMoveItemsToMerchantBuySlots(GuiMerchant gui, boolean fillStacks)
    {
        MerchantRecipeList list = gui.getMerchant().getRecipes(gui.mc.player);
        int index = 0;

        try
        {
            index = this.fieldSelectedMerchantRecipe.getInt(gui);
        }
        catch (IllegalAccessException e)
        {
            ItemScroller.logger.warn("Failed to get the value of GuiMerchant.selectedMerchantRecipe");
        }

        if (list == null || list.size() <= index)
        {
            return;
        }

        MerchantRecipe recipe = list.get(index);
        if (recipe == null)
        {
            return;
        }

        ItemStack buy1 = recipe.getItemToBuy();
        ItemStack buy2 = recipe.getSecondItemToBuy();

        if (isStackEmpty(buy1) == false)
        {
            this.fillBuySlot(gui, 0, buy1, fillStacks);
        }

        if (isStackEmpty(buy2) == false)
        {
            this.fillBuySlot(gui, 1, buy2, fillStacks);
        }
    }

    private void fillBuySlot(GuiContainer gui, int slotNum, ItemStack buyStack, boolean fillStacks)
    {
        Slot slot = gui.inventorySlots.getSlot(slotNum);
        ItemStack existingStack = slot.getStack();

        // If there are items not matching the merchant recipe, move them out first
        if (isStackEmpty(existingStack) == false && areStacksEqual(buyStack, existingStack) == false)
        {
            this.shiftClickSlot(gui, slotNum);
        }

        existingStack = slot.getStack();

        if (isStackEmpty(existingStack) || areStacksEqual(buyStack, existingStack))
        {
            this.moveItemsFromInventory(gui, slotNum, gui.mc.player.inventory, buyStack, fillStacks);
        }
    }

    private boolean tryMoveItemsToCraftingGridSlots(GuiContainer gui, Slot slot, boolean fillStacks)
    {
        Container container = gui.inventorySlots;
        int numSlots = container.inventorySlots.size();
        SlotRange range = Configs.getCraftingGridSlots(gui, slot);

        // Check that the slot range is valid and that the recipe can fit into this type of crafting grid
        if (range != null && range.getLast() < numSlots && this.craftingRecipe.length <= range.getSlotCount())
        {
            // Clear non-matching items from the grid first
            if (this.clearCraftingGridOfNonMatchingItems(gui, range) == false)
            {
                return false;
            }

            // This slot is used to check that we get items from a DIFFERENT inventory than where this slot is in
            Slot slotGridFirst = container.getSlot(range.getFirst());
            Map<ItemType, List<Integer>> ingredientSlots = ItemType.getSlotsPerItem(this.craftingRecipe);

            for (Map.Entry<ItemType, List<Integer>> entry : ingredientSlots.entrySet())
            {
                ItemStack ingredientReference = entry.getKey().getStack();
                List<Integer> recipeSlots = entry.getValue();
                List<Integer> targetSlots = new ArrayList<Integer>();

                // Get the actual target slot numbers based on the grid's start and the relative positions inside the grid
                for (int s : recipeSlots)
                {
                    targetSlots.add(s + range.getFirst());
                }

                if (fillStacks)
                {
                    this.fillCraftingGrid(gui, slotGridFirst, ingredientReference, targetSlots);
                }
                else
                {
                    this.moveOneRecipeItemIntoCraftingGrid(gui, slotGridFirst, ingredientReference, targetSlots);
                }
            }
        }

        return false;
    }

    private boolean clearCraftingGridOfNonMatchingItems(GuiContainer gui, SlotRange range)
    {
        int numSlots = gui.inventorySlots.inventorySlots.size();

        for (int i = 0, slotNum = range.getFirst();
            i < range.getSlotCount() && i < this.craftingRecipe.length && slotNum < numSlots;
            i++, slotNum++)
        {
            Slot slotTmp = gui.inventorySlots.getSlot(slotNum);

            if (slotTmp != null && slotTmp.getHasStack() && areStacksEqual(this.craftingRecipe[i], slotTmp.getStack()) == false)
            {
                this.shiftClickSlot(gui, i);

                // Failed to clear the slot
                if (slotTmp.getHasStack())
                {
                    return false;
                }
            }
        }

        return true;
    }

    private void moveOneRecipeItemIntoCraftingGrid(GuiContainer gui, Slot slotGridFirst, ItemStack ingredientReference, List<Integer> targetSlots)
    {
        Container container = gui.inventorySlots;
        int index = 0;
        int slotNum = -1;
        int slotCount = targetSlots.size();

        while (index < slotCount)
        {
            slotNum = this.getSlotNumberOfSmallestStackFromDifferentInventory(container, slotGridFirst, ingredientReference, slotCount);

            // Didn't find ingredient items
            if (slotNum < 0)
            {
                break;
            }

            // Pick up the ingredient stack from the found slot
            this.leftClickSlot(gui, slotNum);

            // Successfully picked up ingredient items
            if (areStacksEqual(ingredientReference, gui.mc.player.inventory.getItemStack()))
            {
                int filled = this.putSingleItemIntoSlots(gui, targetSlots, index);
                index += filled;

                if (filled < 1)
                {
                    break;
                }
            }
            // Failed to pick up the stack, break to avoid infinite loops
            // TODO: we could also "blacklist" this slot and try to continue...?
            else
            {
                break;
            }
        }

        // Return the rest of the items to the original slot
        if (slotNum >= 0 && isStackEmpty(gui.mc.player.inventory.getItemStack()) == false)
        {
            this.leftClickSlot(gui, slotNum);
        }
    }

    private void fillCraftingGrid(GuiContainer gui, Slot slotGridFirst, ItemStack ingredientReference, List<Integer> targetSlots)
    {
        Container container = gui.inventorySlots;
        EntityPlayer player = gui.mc.player;
        int slotNum = -1;
        int slotReturn = -1;
        int sizeOrig = 0;

        if (isStackEmpty(ingredientReference))
        {
            return;
        }

        while (true)
        {
            slotNum = getSlotNumberOfLargestMatchingStackFromDifferentInventory(container, slotGridFirst, ingredientReference);

            // Didn't find ingredient items
            if (slotNum < 0)
            {
                break;
            }

            if (slotReturn == -1)
            {
                slotReturn = slotNum;
            }

            // Pick up the ingredient stack from the found slot
            this.leftClickSlot(gui, slotNum);

            ItemStack stackCursor = player.inventory.getItemStack();

            // Successfully picked up ingredient items
            if (areStacksEqual(ingredientReference, stackCursor))
            {
                sizeOrig = getStackSize(stackCursor);
                this.dragSplitItemsIntoSlots(gui, targetSlots);
                stackCursor = player.inventory.getItemStack();

                // Items left in cursor
                if (isStackEmpty(stackCursor) == false)
                {
                    // Didn't manage to move any items anymore
                    if (getStackSize(stackCursor) >= sizeOrig)
                    {
                        break;
                    }

                    // Collect all the remaining items into the first found slot, as long as possible
                    this.leftClickSlot(gui, slotReturn);

                    // All of them didn't fit into the first slot anymore, switch into the current source slot
                    if (isStackEmpty(player.inventory.getItemStack()) == false)
                    {
                        slotReturn = slotNum;
                        this.leftClickSlot(gui, slotReturn);
                    }
                }
            }
            // Failed to pick up the stack, break to avoid infinite loops
            // TODO: we could also "blacklist" this slot and try to continue...?
            else
            {
                break;
            }

            // Somehow items were left in the cursor, break here
            if (isStackEmpty(player.inventory.getItemStack()) == false)
            {
                break;
            }
        }

        // Return the rest of the items to the original slot
        if (slotNum >= 0 && isStackEmpty(player.inventory.getItemStack()) == false)
        {
            this.leftClickSlot(gui, slotNum);
        }
    }

    private int putSingleItemIntoSlots(GuiContainer gui, List<Integer> targetSlots, int startIndex)
    {
        ItemStack stackInCursor = gui.mc.player.inventory.getItemStack();

        if (isStackEmpty(stackInCursor))
        {
            return 0;
        }

        int numSlots = gui.inventorySlots.inventorySlots.size();
        int numItems = getStackSize(stackInCursor);
        int loops = Math.min(numItems, targetSlots.size() - startIndex);
        int count = 0;

        for (int i = 0; i < loops; i++)
        {
            int slotNum = targetSlots.get(startIndex + i);

            if (slotNum >= numSlots)
            {
                break;
            }

            this.rightClickSlot(gui, slotNum);
            count++;
        }

        return count;
    }

    private void moveOneSetOfItemsFromSlotToOtherInventory(GuiContainer gui, Slot slot)
    {
        this.leftClickSlot(gui, slot.slotNumber);

        ItemStack stackCursor = gui.mc.player.inventory.getItemStack();

        if (isStackEmpty(stackCursor) == false)
        {
            List<Integer> slots = this.getSlotNumbersOfMatchingStacksFromDifferentInventory(gui.inventorySlots, slot, stackCursor, true);

            if (this.moveItemFromCursorToSlots(gui, slots) == false)
            {
                slots = this.getSlotNumbersOfEmptySlotsFromDifferentInventory(gui.inventorySlots, slot);
                this.moveItemFromCursorToSlots(gui, slots);
            }
        }
    }

    private boolean moveItemFromCursorToSlots(GuiContainer gui, List<Integer> slotNumbers)
    {
        net.minecraft.entity.player.InventoryPlayer inv = gui.mc.player.inventory;

        for (int slotNum : slotNumbers)
        {
            this.leftClickSlot(gui, slotNum);

            if (isStackEmpty(inv.getItemStack()))
            {
                return true;
            }
        }

        return false;
    }

    private void moveItemsFromInventory(GuiContainer gui, int slotTo, IInventory invSrc, ItemStack stackTemplate, boolean fillStacks)
    {
        Container container = gui.inventorySlots;

        for (Slot slot : container.inventorySlots)
        {
            if (slot == null)
            {
                continue;
            }

            if (slot.inventory == invSrc && areStacksEqual(stackTemplate, slot.getStack()))
            {
                if (fillStacks)
                {
                    if (this.clickSlotsToMoveItems(gui, slot.slotNumber, slotTo) == false)
                    {
                        break;
                    }
                }
                else
                {
                    this.clickSlotsToMoveSingleItem(gui, slot.slotNumber, slotTo);
                    break;
                }
            }
        }
    }

    private int getSlotNumberOfLargestMatchingStackFromDifferentInventory(Container container, Slot slotReference, ItemStack stackReference)
    {
        int slotNum = -1;
        int largest = 0;

        for (Slot slot : container.inventorySlots)
        {
            if (areSlotsInSameInventory(slot, slotReference) == false && slot.getHasStack() &&
                areStacksEqual(stackReference, slot.getStack()))
            {
                int stackSize = getStackSize(slot.getStack());

                if (stackSize > largest)
                {
                    slotNum = slot.slotNumber;
                    largest = stackSize;
                }
            }
        }

        return slotNum;
    }

    /**
     * Returns the slot number of the slot that has the smallest stackSize that is still equal to or larger
     * than idealSize. The slot must also NOT be in the same inventory as slotReference.
     * If an adequately large stack is not found, then the largest one is selected.
     * @param container
     * @param slotReference
     * @param stackReference
     * @return
     */
    private int getSlotNumberOfSmallestStackFromDifferentInventory(Container container, Slot slotReference, ItemStack stackReference, int idealSize)
    {
        int slotNum = -1;
        int smallest = Integer.MAX_VALUE;

        for (Slot slot : container.inventorySlots)
        {
            if (areSlotsInSameInventory(slot, slotReference) == false && slot.getHasStack() &&
                areStacksEqual(stackReference, slot.getStack()))
            {
                int stackSize = getStackSize(slot.getStack());

                if (stackSize < smallest && stackSize >= idealSize)
                {
                    slotNum = slot.slotNumber;
                    smallest = stackSize;
                }
            }
        }

        // Didn't find an adequately sized stack, now try to find at least some items...
        if (slotNum == -1)
        {
            int largest = 0;

            for (Slot slot : container.inventorySlots)
            {
                if (areSlotsInSameInventory(slot, slotReference) == false && slot.getHasStack() &&
                    areStacksEqual(stackReference, slot.getStack()))
                {
                    int stackSize = getStackSize(slot.getStack());

                    if (stackSize > largest)
                    {
                        slotNum = slot.slotNumber;
                        largest = stackSize;
                    }
                }
            }
        }

        return slotNum;
    }

    /**
     * Return the slot numbers of slots that have items identical to stackReference, that are NOT in the same
     * inventory as slotReference. If preferPartial is true, then stacks with a stackSize less that getMaxStackSize() are
     * at the beginning of the list (not ordered though) and full stacks are at the end, otherwise the reverse is true.
     * @param container
     * @param slotReference
     * @param stackReference
     * @param preferPartial
     * @return
     */
    private List<Integer> getSlotNumbersOfMatchingStacksFromDifferentInventory(Container container, Slot slotReference,
            ItemStack stackReference, boolean preferPartial)
    {
        List<Integer> slots = new ArrayList<Integer>(64);

        for (int i = container.inventorySlots.size() - 1; i >= 0; i--)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.getHasStack() &&
                areSlotsInSameInventory(slot, slotReference) == false &&
                areStacksEqual(slot.getStack(), stackReference))
            {
                if ((getStackSize(slot.getStack()) < stackReference.getMaxStackSize()) == preferPartial)
                {
                    slots.add(0, slot.slotNumber);
                }
                else
                {
                    slots.add(slot.slotNumber);
                }
            }
        }

        return slots;
    }

    private List<Integer> getSlotNumbersOfEmptySlotsFromDifferentInventory(Container container, Slot slotReference)
    {
        List<Integer> slots = new ArrayList<Integer>(64);

        for (int i = container.inventorySlots.size() - 1; i >= 0; i--)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.getHasStack() == false && areSlotsInSameInventory(slot, slotReference) == false)
            {
                slots.add(slot.slotNumber);
            }
        }

        return slots;
    }

    private static boolean areStacksEqual(ItemStack stack1, ItemStack stack2)
    {
        return ItemStack.areItemsEqual(stack1, stack2) && ItemStack.areItemStackTagsEqual(stack1, stack2);
    }

    private static boolean areSlotsInSameInventory(Slot slot1, Slot slot2)
    {
        return slot1.isSameInventory(slot2);
    }

    private ItemStack[] getOriginalStacks(Container container)
    {
        ItemStack[] originalStacks = new ItemStack[container.inventorySlots.size()];

        for (int i = 0; i < originalStacks.length; i++)
        {
            originalStacks[i] = ItemStack.copyItemStack(container.inventorySlots.get(i).getStack());
        }

        return originalStacks;
    }

    private void restoreOriginalStacks(Container container, ItemStack[] originalStacks)
    {
        for (int i = 0; i < originalStacks.length; i++)
        {
            ItemStack stackSlot = container.getSlot(i).getStack();

            if (areStacksEqual(stackSlot, originalStacks[i]) == false ||
                (isStackEmpty(stackSlot) == false && getStackSize(stackSlot) != getStackSize(originalStacks[i])))
            {
                container.putStackInSlot(i, originalStacks[i]);
            }
        }
    }

    private int getTargetSlot(Container container, ItemStack[] originalStacks)
    {
        List<Slot> slots = container.inventorySlots;

        for (int i = 0; i < originalStacks.length; i++)
        {
            ItemStack stackOrig = originalStacks[i];
            ItemStack stackNew = slots.get(i).getStack();

            if ((isStackEmpty(stackOrig) && isStackEmpty(stackNew) == false) ||
               (isStackEmpty(stackOrig) == false && isStackEmpty(stackNew) == false &&
               getStackSize(stackNew) == (getStackSize(stackOrig) + 1)))
            {
                return i;
            }
        }

        return -1;
    }

    /*
    private void clickSlotsToMoveItems(Slot slot, GuiContainer gui, boolean matchingOnly, boolean toOtherInventory)
    {
        for (Slot slotTmp : gui.inventorySlots.inventorySlots)
        {
            if (slotTmp.slotNumber != slot.slotNumber && areSlotsInSameInventory(slotTmp, slot) == toOtherInventory &&
                slotTmp.getHasStack() && (matchingOnly == false || areStacksEqual(slot.getStack(), slotTmp.getStack())))
            {
                this.clickSlotsToMoveItemsFromSlot(slotTmp, gui, toOtherInventory);
                return;
            }
        }

        // Move the hovered-over slot's stack last
        if (toOtherInventory)
        {
            this.clickSlotsToMoveItemsFromSlot(slot, gui, toOtherInventory);
        }
    }
    */

    private void clickSlotsToMoveItemsFromSlot(Slot slotFrom, GuiContainer gui, boolean toOtherInventory)
    {
        EntityPlayer player = gui.mc.player;
        // Left click to pick up the found source stack
        this.leftClickSlot(gui, slotFrom.slotNumber);

        if (isStackEmpty(player.inventory.getItemStack()))
        {
            return;
        }

        for (Slot slotDst : gui.inventorySlots.inventorySlots)
        {
            ItemStack stackDst = slotDst.getStack();

            if (areSlotsInSameInventory(slotDst, slotFrom) != toOtherInventory &&
                (isStackEmpty(stackDst) || areStacksEqual(stackDst, player.inventory.getItemStack())))
            {
                // Left click to (try and) place items to the slot
                this.leftClickSlot(gui, slotDst.slotNumber);
            }

            if (isStackEmpty(player.inventory.getItemStack()))
            {
                return;
            }
        }

        // Couldn't fit the entire stack to the target inventory, return the rest of the items
        if (isStackEmpty(player.inventory.getItemStack()) == false)
        {
            this.leftClickSlot(gui, slotFrom.slotNumber);
        }
    }

    private boolean clickSlotsToMoveSingleItem(GuiContainer gui, int slotFrom, int slotTo)
    {
        //System.out.println("clickSlotsToMoveSingleItem(from: " + slotFrom + ", to: " + slotTo + ")");
        ItemStack stack = gui.inventorySlots.inventorySlots.get(slotFrom).getStack();

        if (isStackEmpty(stack))
        {
            return false;
        }

        // Click on the from-slot to take items to the cursor - if there is more than one item in the from-slot,
        // right click on it, otherwise left click.
        if (getStackSize(stack) > 1)
        {
            this.rightClickSlot(gui, slotFrom);
        }
        else
        {
            this.leftClickSlot(gui, slotFrom);
        }

        // Right click on the target slot to put one item to it
        this.rightClickSlot(gui, slotTo);

        // If there are items left in the cursor, then return them back to the original slot
        if (isStackEmpty(gui.mc.player.inventory.getItemStack()) == false)
        {
            // Left click again on the from-slot to return the rest of the items to it
            this.leftClickSlot(gui, slotFrom);
        }

        return true;
    }

    private boolean clickSlotsToMoveSingleItemByShiftClick(GuiContainer gui, int slotFrom)
    {
        Slot slot = gui.inventorySlots.inventorySlots.get(slotFrom);
        ItemStack stack = slot.getStack();

        if (isStackEmpty(stack))
        {
            return false;
        }

        if (getStackSize(stack) > 1)
        {
            // Left click on the from-slot to take all the items to the cursor
            this.leftClickSlot(gui, slotFrom);

            // Still items left in the slot, put the stack back and abort
            if (slot.getHasStack())
            {
                this.leftClickSlot(gui, slotFrom);
                return false;
            }
            else
            {
                // Right click one item back to the slot
                this.rightClickSlot(gui, slotFrom);
            }
        }

        // ... and then shift-click on the slot
        this.shiftClickSlot(gui, slotFrom);

        if (isStackEmpty(gui.mc.player.inventory.getItemStack()) == false)
        {
            // ... and then return the rest of the items
            this.leftClickSlot(gui, slotFrom);
        }

        return true;
    }

    /**
     * Try move items from slotFrom to slotTo
     * @return true if at least some items were moved
     */
    private boolean clickSlotsToMoveItems(GuiContainer gui, int slotFrom, int slotTo)
    {
        EntityPlayer player = gui.mc.player;
        //System.out.println("clickSlotsToMoveItems(from: " + slotFrom + ", to: " + slotTo + ")");

        // Left click to take items
        this.leftClickSlot(gui, slotFrom);

        // Couldn't take the items, bail out now
        if (isStackEmpty(player.inventory.getItemStack()))
        {
            return false;
        }

        boolean ret = true;
        int size = getStackSize(player.inventory.getItemStack());

        // Left click on the target slot to put the items to it
        this.leftClickSlot(gui, slotTo);

        // If there are items left in the cursor, then return them back to the original slot
        if (isStackEmpty(player.inventory.getItemStack()) == false)
        {
            ret = getStackSize(player.inventory.getItemStack()) != size;

            // Left click again on the from-slot to return the rest of the items to it
            this.leftClickSlot(gui, slotFrom);
        }

        return ret;
    }

    private boolean shiftClickSlotWithCheck(GuiContainer gui, int slotNum)
    {
        Slot slot = gui.inventorySlots.getSlot(slotNum);

        if (slot == null || slot.getHasStack() == false)
        {
            return false;
        }

        int sizeOrig = getStackSize(slot.getStack());
        this.shiftClickSlot(gui, slotNum);

        return slot.getHasStack() == false || getStackSize(slot.getStack()) != sizeOrig;
    }

    private void leftClickSlot(GuiContainer gui, int slot)
    {
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, slot, 0, ClickType.PICKUP, gui.mc.player);
    }

    private void rightClickSlot(GuiContainer gui, int slot)
    {
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, slot, 1, ClickType.PICKUP, gui.mc.player);
    }

    private void shiftClickSlot(GuiContainer gui, int slot)
    {
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, slot, 0, ClickType.QUICK_MOVE, gui.mc.player);
    }

    private void dropItemsFromCursor(GuiContainer gui)
    {
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, -999, 0, ClickType.PICKUP, gui.mc.player);
    }

    private void dropStack(GuiContainer gui, int slot)
    {
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, slot, 1, ClickType.THROW, gui.mc.player);
    }

    private void dragSplitItemsIntoSlots(GuiContainer gui, List<Integer> targetSlots)
    {
        ItemStack stackInCursor = gui.mc.player.inventory.getItemStack();

        if (isStackEmpty(stackInCursor))
        {
            return;
        }

        if (targetSlots.size() == 1)
        {
            this.leftClickSlot(gui, targetSlots.get(0));
            return;
        }

        int numSlots = gui.inventorySlots.inventorySlots.size();
        int loops = targetSlots.size();

        // Start the drag
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, -999, 0, ClickType.QUICK_CRAFT, gui.mc.player);

        for (int i = 0; i < loops; i++)
        {
            int slotNum = targetSlots.get(i);

            if (slotNum >= numSlots)
            {
                break;
            }

            gui.mc.playerController.windowClick(gui.inventorySlots.windowId, targetSlots.get(i), 1, ClickType.QUICK_CRAFT, gui.mc.player);
        }

        // End the drag
        gui.mc.playerController.windowClick(gui.inventorySlots.windowId, -999, 2, ClickType.QUICK_CRAFT, gui.mc.player);
    }

    /**************************************************************
     * Compatibility code for pre-1.11 vs. 1.11+
     * Well kind of, as in make the differences minimal,
     * only requires changing these things for the ItemStack
     * related changes.
     *************************************************************/

    public static final ItemStack EMPTY_STACK = null;

    public static boolean isStackEmpty(ItemStack stack)
    {
        return stack == null;
    }

    public static int getStackSize(ItemStack stack)
    {
        return stack.stackSize;
    }

    public static void setStackSize(ItemStack stack, int size)
    {
        stack.stackSize = size;
    }
}
