package nl.dgoossens.autocraft.helpers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import nl.dgoossens.autocraft.AutomatedCrafting;
import nl.dgoossens.autocraft.api.CraftingRecipe;
import nl.dgoossens.autocraft.api.RecipeType;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * Build a recipe from item stacks in code.
 * These recipes have a prebuilt list of items they search for that
 * can be re-used for both testing if the items are contained in the
 * inventory as for taking them.
 */
public class BukkitRecipe implements CraftingRecipe {
    private RecipeType type = RecipeType.UNKNOWN;
    private final ItemStack result;
    private Set<RecipeRequirement> requirements;

    //Shaped Recipes
    private String[] pattern;
    private Map<Character, Collection<ItemStack>> key;

    //Shapeless Recipes
    private Collection<Collection<ItemStack>> ingredients;

    //A few NMS classes we use because 1.12 is outdated and doesn't support cool recipes yet.
    private static final Class<?> recipeChoice = findClass("org.bukkit.inventory.RecipeChoice").orElse(null);
    private static final Class<?> exactChoice = Optional.ofNullable(recipeChoice).map(f -> f.getDeclaredClasses()[0]).orElse(null);
    private static final Class<?> materialChoice = Optional.ofNullable(recipeChoice).map(f -> f.getDeclaredClasses()[1]).orElse(null);

    private static final Method getChoiceMapMethod = ReflectionHelper.getMethod(ShapedRecipe.class, "getChoiceMap").orElse(null);
    private static final Method recipeChoiceGetItemStackMethod = ReflectionHelper.getMethod(recipeChoice, "getItemStack").orElse(null);
    private static final Method getChoiceListMethod = ReflectionHelper.getMethod(ShapelessRecipe.class, "getChoiceList").orElse(null);

    //Get a class and put it in an optional.
    private static Optional<Class<?>> findClass(String className) {
        try {
            return Optional.ofNullable(Class.forName(className));
        } catch (Exception x) {
            return Optional.empty();
        }
    }

    public BukkitRecipe(ItemStack result, String[] pattern, Map<Character, Collection<ItemStack>> key) {
        type = RecipeType.SHAPED;
        this.result = result;
        this.pattern = pattern;
        this.key = key;
    }

    public BukkitRecipe(ItemStack result, List<Collection<ItemStack>> ingredients) {
        type = RecipeType.SHAPELESS;
        this.result = result;
        this.ingredients = ingredients;
    }

    /**
     * Build a recipe from a bukkit recipe.
     */
    public BukkitRecipe(Recipe bukkitRecipe) {
        result = bukkitRecipe.getResult();
        if (bukkitRecipe instanceof ShapedRecipe) {
            type = RecipeType.SHAPED;
            pattern = ((ShapedRecipe) bukkitRecipe).getShape();

            // since late 1.13+ we have the new choice map system
            if (exactChoice != null) {
                try {
                    key = new HashMap<>();
                    Map<Character, Object> choiceMap = (Map<Character, Object>) getChoiceMapMethod.invoke(bukkitRecipe);
                    choiceMap.forEach((k, v) -> {
                        List<ItemStack> values = new ArrayList<>();
                        if (v != null) { //V can be null for some reason.
                            if (exactChoice.isAssignableFrom(v.getClass()) || materialChoice.isAssignableFrom(v.getClass())) {
                                try {
                                    List<Object> choices = (List<Object>) v.getClass().getMethod("getChoices").invoke(v);
                                    for (Object o : choices) {
                                        if (o instanceof Material) values.add(new ItemStack((Material) o));
                                        else values.add((ItemStack) o);
                                    }
                                } catch (Exception x) {
                                    x.printStackTrace();
                                }
                            } else {
                                ItemStack val = null;
                                try {
                                    val = (ItemStack) recipeChoiceGetItemStackMethod.invoke(v);
                                } catch (Exception x) {
                                    x.printStackTrace();
                                }
                                if (val != null) values.add(val);
                            }
                        }
                        key.put(k, values);
                    });
                } catch (Exception x) {
                    x.printStackTrace();
                }
            } else {
                key = new HashMap<>();
                ((ShapedRecipe) bukkitRecipe).getIngredientMap().forEach((k, v) -> {
                    if (v == null) return;
                    key.put(k, Collections.singletonList(v));
                });
            }
        } else if (bukkitRecipe instanceof ShapelessRecipe) {
            type = RecipeType.SHAPELESS;

            // since late 1.13+ we have the new choice map system
            if (exactChoice != null) {
                try {
                    ingredients = new ArrayList<>();
                    List<Object> choiceList = (List<Object>) getChoiceListMethod.invoke(bukkitRecipe);
                    choiceList.forEach(v -> {
                        List<ItemStack> values = new ArrayList<>();
                        if (v != null) { //V can be null for some reason.
                            if (exactChoice.isAssignableFrom(v.getClass()) || materialChoice.isAssignableFrom(v.getClass())) {
                                try {
                                    List<Object> choices = (List<Object>) v.getClass().getMethod("getChoices").invoke(v);
                                    for (Object o : choices) {
                                        if (o instanceof Material) values.add(new ItemStack((Material) o));
                                        else values.add((ItemStack) o);
                                    }
                                } catch (Exception x) {
                                    x.printStackTrace();
                                }
                            } else {
                                ItemStack val = null;
                                try {
                                    val = (ItemStack) recipeChoiceGetItemStackMethod.invoke(v);
                                } catch (Exception x) {
                                    x.printStackTrace();
                                }
                                if (val != null) values.add(val);
                            }
                        }
                        ingredients.add(values);
                    });
                } catch (Exception x) {
                    x.printStackTrace();
                }
            } else {
                ingredients = ((ShapelessRecipe) bukkitRecipe).getIngredientList().stream().map(Collections::singletonList).collect(Collectors.toList());
            }
        }
    }

    public RecipeType getType() {
        return type;
    }

    /**
     * The requirements map for this recipe, can be cached.
     */
    private Set<RecipeRequirement> getRequirements() {
        if (requirements == null) {
            requirements = new HashSet<>();
            switch (type) {
                case SHAPED:
                    //Count how many times each character in the pattern occurrences
                    //Crafting recipes cannot have two items in a single slot.
                    Map<Character, Integer> occurrences = new HashMap<>();
                    for (String s : pattern) {
                        for (char c : s.toCharArray()) {
                            occurrences.put(c, occurrences.getOrDefault(c, 0) + 1);
                        }
                    }
                    //Put the corresponding item for each part of the shape into the requirements list
                    occurrences.forEach((c, i) -> {
                        RecipeRequirement rr = new RecipeRequirement(key.getOrDefault(c, new HashSet<>()), i);
                        //Return if invalid (key does not exit in map)
                        if (rr.isInvalid()) {
                            AutomatedCrafting.getInstance().warning("Warning shaped recipe with pattern [[" + String.join("], [", pattern) + "]] had character in pattern not in key map.");
                            return;
                        }

                        //Try to merge this recipe requirement and otherwise add it
                        if (!requirements.stream().filter(r -> r.overlap(rr)).map(r -> r.increment(rr.amount)).findAny().isPresent())
                            requirements.add(rr);
                    });
                    break;
                case SHAPELESS:
                    ingredients.forEach(i -> {
                        RecipeRequirement rr = new RecipeRequirement(i, 1);

                        //Try to merge this recipe requirement and otherwise add it
                        if (!requirements.stream().filter(r -> r.overlap(rr)).map(r -> r.increment(rr.amount)).findAny().isPresent())
                            requirements.add(rr);
                    });
                    break;
            }
        }
        return requirements;
    }

    @Override
    public boolean containsRequirements(Inventory inv) {
        Set<RecipeRequirement> requirements = getRequirements();

        //Test if any requirements are NOT met
        ItemStack[] contents = new ItemStack[inv.getStorageContents().length];
        for (int j = 0; j < contents.length; j++) {
            if (inv.getStorageContents()[j] != null)
                contents[j] = inv.getStorageContents()[j].clone(); //Build clones so we can track which items we've already used as a component.
        }
        for (RecipeRequirement r : requirements) {
            if (!r.isContainedInInventory(contents))
                return false;
        }
        return true;
    }

    @Override
    public ArrayList<ItemStack> takeMaterials(Inventory inv) {
        Set<RecipeRequirement> requirements = getRequirements();
        ArrayList<ItemStack> ret = new ArrayList<>();
        requirements.stream().forEach(rr -> {
            int amountToTake = rr.amount;
            //Try each item one by one to see if we can take that item from our inventory.
            for (ItemStack i : rr.item) {
                int remain = takeFromInventory(inv, i, amountToTake);
                //This item was used and something was taken
                if (remain != amountToTake) {
                    ItemStack res = getContainerItem(i);
                    if (res != null) {
                        //How many did we take, that's how many of this container item should be put back.
                        res.setAmount(Math.max(0, amountToTake - remain));
                        if (res.getAmount() > 0)
                            ret.add(res);
                    }
                }
                amountToTake = remain;
                //We don't need to keep trying to complete this requirement if we've already done so.
                if (amountToTake <= 0) break;
            }
        });
        return ret;
    }

    public int takeFromInventory(Inventory inv, ItemStack item, int limit) {
        int ret = limit;
        if (item != null) {
            ItemStack[] its = inv.getStorageContents();
            for (int j = 0; j < its.length; ++j) {
                ItemStack i = its[j];
                if (isSimilar(item, i)) {
                    int cap = Math.min(ret, i.getAmount());
                    if (i.getAmount() - cap <= 0) inv.setItem(j, null);
                    else i.setAmount(i.getAmount() - cap);
                    ret -= cap;
                    if (ret <= 0) return ret;
                }
            }

        }
        return ret;
    }

    @Override
    public boolean creates(ItemStack stack) {
        return isSimilar(result, stack);
    }

    @Override
    public ItemStack getResultDrop() {
        return result.clone();
    }

    /**
     * A single recipe requirement.
     * Each recipe requirement is unique and no two recipe requirements
     * can contain similar items.
     * <p>
     * Due to technical limitations weird edge-cases may occur when two requirements
     * are able to use the same item for crafting.
     */
    public static class RecipeRequirement {
        private final Collection<ItemStack> item;
        private int amount;

        public RecipeRequirement(Collection<ItemStack> items, int amount) {
            this.item = items;
            this.amount = amount;
        }

        private boolean isInvalid() {
            return item == null || item.isEmpty();
        }

        //Increment the amount of this recipe with the given amount.
        private RecipeRequirement increment(int amount) {
            this.amount += amount;
            return this;
        }

        /**
         * Returns true if two requirements overlap.
         */
        private boolean overlap(RecipeRequirement other) {
            for (ItemStack i : item) {
                //If any item items overlap between the two we call them overlapping.
                if (other.item.stream().anyMatch(j -> isSimilar(i, j))) {
                    if (item.size() != other.item.size())
                        AutomatedCrafting.getInstance().warning("A recipe incorrectly merged two recipe requirements, please make sure in recipes no two slots are allowed to contain the same item unless they are fully identical. E.g. don't have a shapeless recipe with paper and paper or leather.");
                    return true;
                }
            }
            return false;
        }

        private boolean isContainedInInventory(ItemStack[] itemList) {
            int amountToFind = amount;
            for (ItemStack it : itemList) {
                //If any item in our array of valid items is similar to this item we have found our match.
                if (item.stream().anyMatch(f -> isSimilar(f, it))) {
                    int cap = Math.min(it.getAmount(), amountToFind);
                    it.setAmount(it.getAmount() - cap); //Decrease item by amount so we can use it again for the next item.
                    amountToFind -= cap;

                    //If we have at least the amount of any valid item in this inventory we call it good.
                    if (amountToFind <= 0)
                        return true;
                }
            }
            return false;
        }
    }

    /**
     * Custom isSimilar implementation that supports ingredients with a
     * durability of -1.
     */
    public static boolean isSimilar(ItemStack a, ItemStack b) {
        // Documentation is a bit vague but it appears ingredients with -1 mean
        // the metadata isn't important and it should accept any type. We always
        // pass the ingredient as a so if a has a durability of -1 we only compare
        // materials. (Bukkit changes -1 to Short.MAX_VALUE)
        if (a != null && b != null && a.getDurability() == Short.MAX_VALUE) {
            return a.getType() == b.getType();
        }
        return a != null && a.isSimilar(b);
    }
}
