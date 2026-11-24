package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.BiomeCandidateSearch;
import cn.net.rms.confluxmap.core.predict.BiomeSearchService;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.SearchOrder;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Candidate query and result actions for one biome. */
final class BiomeCandidateScreen extends CandidateQueryScreen<BiomeCandidateSearch.Candidate> {
    private final FullscreenMapScreen map;
    private final DimensionId dimension;
    private final Identifier biome;
    private List<BiomeCandidateSearch.Candidate> results = List.of();

    BiomeCandidateScreen(
        final StructureSearchScreen picker,
        final FullscreenMapScreen map,
        final DimensionId dimension,
        final Identifier biome
    ) {
        super(map, picker, Texts.translatable(
            "confluxmap.screen.biome_candidates.title",
            StructureSearchScreen.localizedBiomeName(biome)
        ));
        this.map = map;
        this.dimension = dimension;
        this.biome = biome;
    }

    @Override
    protected List<BiomeCandidateSearch.Candidate> results() {
        return results;
    }

    @Override
    protected Text searchButtonLabel() {
        return Texts.translatable(searchButtonKey(searching));
    }

    @Override
    protected void afterTick() {
        searchButton.active = !searching;
        if (orderButton != null) {
            orderButton.active = !searching;
            orderButton.setMessage(Texts.translatable(CandidateListUi.orderLabelKey(order)));
        }
    }

    @Override
    protected void submitSearch() {
        final int generation = ++queryGeneration;
        final int queryCenterX = centerX;
        final int queryCenterZ = centerZ;
        final int queryRadius = radius;
        final int queryLimit = limit;
        final SearchOrder queryOrder = order;
        final ColumnStore store = map.biomeSearchStore();
        final OptionalInt predictedBiome = biome.getNamespace().equals("minecraft")
            ? CubiomesBiomeIds.idForName(biome.getPath())
            : OptionalInt.empty();
        final BaselineSampler sampler = predictedBiome.isPresent()
            ? map.biomeSearchSampler(dimension)
            : null;
        searching = true;
        statusKey = "confluxmap.screen.biome_candidates.searching";
        scrollOffset = 0;
        rebuild();
        try {
            final MapExecutors executors = ConfluxMapClient.get().executors();
            executors.workers().execute(() -> {
                final List<BiomeCandidateSearch.Candidate> found = BiomeSearchService.search(
                    store,
                    biome.toString(),
                    predictedBiome,
                    sampler,
                    queryCenterX,
                    queryCenterZ,
                    queryRadius,
                    queryLimit,
                    queryOrder,
                    executors.workers(),
                    // The search already occupies one worker and runs the first row band
                    // itself, so workerCount-1 bands still leave a worker for tile compositing.
                    Math.max(1, executors.workerCount() - 1)
                );
                MinecraftClient.getInstance().execute(() -> acceptResults(
                    generation, found, sampler != null
                ));
            });
        } catch (final RejectedExecutionException e) {
            searching = false;
            statusKey = "confluxmap.screen.biome_candidates.failed";
            rebuild();
        }
    }

    private void acceptResults(
        final int generation,
        final List<BiomeCandidateSearch.Candidate> found,
        final boolean predictionAvailable
    ) {
        if (generation != queryGeneration
            || MinecraftAccess.screen(MinecraftClient.getInstance()) != this) {
            return;
        }
        results = found;
        searching = false;
        statusKey = found.isEmpty()
            ? predictionAvailable
                ? "confluxmap.screen.biome_candidates.not_found"
                : "confluxmap.screen.biome_candidates.not_found_no_prediction"
            : null;
        if (!found.isEmpty()) {
            focusRow(found.get(0));
        }
        rebuild();
    }

    @Override
    protected void focusRow(final BiomeCandidateSearch.Candidate candidate) {
        map.focusBiome(candidate);
    }

    @Override
    protected String rowText(final BiomeCandidateSearch.Candidate candidate) {
        return CandidateListUi.coordinateText(candidate.blockX(), candidate.blockZ())
            + " · "
            + Texts.translatable(
                "confluxmap.value.blocks",
                CandidateListUi.distanceInBlocks(
                    candidate.blockX(), candidate.blockZ(), centerX, centerZ
                )
            ).getString();
    }

    @Override
    protected void createWaypoint(final BiomeCandidateSearch.Candidate candidate) {
        map.createWaypointForBiome(StructureSearchScreen.localizedBiomeName(biome), candidate, this);
    }

    @Override
    protected void drawRow(final GuiDraw draw, final CandidateListUi listUi, final int index) {
        final BiomeCandidateSearch.Candidate candidate = results.get(index);
        draw.drawTextWithShadow(
            this.textRenderer,
            fitToWidth(rowText(candidate), listUi.textWidth()),
            rowX(),
            listUi.rowY(index) + 6,
            0xFFFFFFFF
        );
    }

    static String searchButtonKey(final boolean searching) {
        return searching
            ? "confluxmap.screen.biome_candidates.searching_button"
            : "confluxmap.screen.structure_candidates.search";
    }

    static int statusY(final int screenHeight) {
        return screenHeight - 36;
    }
}
