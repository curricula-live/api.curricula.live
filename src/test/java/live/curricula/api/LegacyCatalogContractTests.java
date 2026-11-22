package live.curricula.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;
import live.curricula.api.catalog.ConceptSummary;
import live.curricula.api.catalog.JenaKnowledgeCatalog;
import live.curricula.api.http.LegacyCatalogController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyCatalogContractTests {
    @Test void legacyReadShapesAndRankingAvoidUnmappedRelations() throws Exception {
        var catalog = mock(JenaKnowledgeCatalog.class);
        when(catalog.canonicalSlugs()).thenReturn(List.of("a-binary-search", "binary-search", "binarysearch"));
        when(catalog.findBySlug("binary-search")).thenReturn(Optional.of(
                new ConceptSummary("https://curricula.live/id/formal-sciences/concept/binary-search", "binary-search", "Binary search")));
        var mvc = MockMvcBuilders.standaloneSetup(new LegacyCatalogController(catalog)).build();
        mvc.perform(get("/health/")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(jsonPath("$.versions.v1").value("/v1/"));
        mvc.perform(get("/v1/concepts/")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].slug").value("a-binary-search"));
        mvc.perform(get("/v1/concepts/binary-search/")).andExpect(status().isOk())
                .andExpect(content().json("{\"slug\":\"binary-search\"}"));
        mvc.perform(get("/v1/concepts/unmapped/")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/search/").param("q", " Binary_search ").param("category", "concepts").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.query").value("Binary_search"))
                .andExpect(jsonPath("$.results.concepts[0].slug").value("binary-search"))
                .andExpect(jsonPath("$.results.concepts[1].slug").value("binarysearch"));
        mvc.perform(get("/v1/search/").param("q", "!").param("category", "concepts"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/search/").param("q", "binary-search")).andExpect(status().isNotImplemented());
    }
}
