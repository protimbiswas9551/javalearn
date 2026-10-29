package com.javalearn;

import com.javalearn.Models.Project;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectSortTest {

    @Test
    public void testSortOptionsHasOnlyTwoChoices() {
        Map<String, String> options = Service.sortOptions(true);
        assertEquals(2, options.size());
        assertEquals("Name (A to Z)", options.get("name-asc"));
        assertEquals("Name (Z to A)", options.get("name-desc"));
        assertNull(options.get("difficulty-asc"));
        assertNull(options.get("difficulty-desc"));
        assertNull(options.get("updated"));
    }

    @Test
    public void testSearchProjectsAscendingAndDescending() {
        Project p1 = new Project("zebra-app", "Zebra App", "desc", "Beginner", List.of(), List.of("Main.java"), true, "url", null);
        Project p2 = new Project("alpha-app", "Alpha App", "desc", "Advanced", List.of(), List.of("Main.java"), true, "url", null);
        Project p3 = new Project("beta-app", "beta app", "desc", "Intermediate", List.of(), List.of("Main.java"), true, "url", null);

        List<Project> list = List.of(p1, p2, p3);

        List<Project> sortedAsc = Service.searchProjects(list, "", "", "", "name-asc");
        assertEquals("Alpha App", sortedAsc.get(0).displayName());
        assertEquals("beta app", sortedAsc.get(1).displayName());
        assertEquals("Zebra App", sortedAsc.get(2).displayName());

        List<Project> sortedDesc = Service.searchProjects(list, "", "", "", "name-desc");
        assertEquals("Zebra App", sortedDesc.get(0).displayName());
        assertEquals("beta app", sortedDesc.get(1).displayName());
        assertEquals("Alpha App", sortedDesc.get(2).displayName());
    }
}

