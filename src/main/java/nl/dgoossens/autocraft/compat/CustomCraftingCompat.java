package nl.dgoossens.autocraft.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.wolfyscript.customcrafting.CustomCrafting;
import me.wolfyscript.customcrafting.handlers.RecipeHandler;
import me.wolfyscript.customcrafting.items.CustomItem;
import me.wolfyscript.customcrafting.recipes.workbench.CraftingRecipe;
import me.wolfyscript.customcrafting.recipes.workbench.ShapedCraftRecipe;
import me.wolfyscript.customcrafting.recipes.workbench.ShapelessCraftRecipe;
import nl.dgoossens.autocraft.AutomatedCrafting;
import nl.dgoossens.autocraft.helpers.JsonItem;
import nl.dgoossens.autocraft.helpers.Recipe;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compatibility with:
 * CustomCrafting by WolfyScript
 */
public class CustomCraftingCompat implements CompatClass {
    public void load(final Set<Recipe> loadedRecipes) {
        RecipeHandler recipeHandler = CustomCrafting.getRecipeHandler();
        for(CraftingRecipe cr : recipeHandler.getCraftingRecipes()) {
            if(cr instanceof ShapedCraftRecipe) {
                loadedRecipes.add(new Recipe(new JsonItem(cr.getCustomResult()), ((ShapedCraftRecipe) cr).getShape(), cr.getIngredients()
                        .entrySet().parallelStream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
                            JsonArray ja = new JsonArray();
                            for(CustomItem j : e.getValue())
                                ja.add(AutomatedCrafting.GSON.toJsonTree(new JsonItem(j)));
                            return (JsonElement) ja;
                        }))));
            } else if(cr instanceof ShapelessCraftRecipe) {
                loadedRecipes.add(new Recipe(new JsonItem(cr.getCustomResult()), ((ShapelessCraftRecipe) cr).getIngredientList()
                        .parallelStream().map(f -> AutomatedCrafting.GSON.toJsonTree(new JsonItem(f))).collect(Collectors.toSet())));
            }
        }
    }
}
