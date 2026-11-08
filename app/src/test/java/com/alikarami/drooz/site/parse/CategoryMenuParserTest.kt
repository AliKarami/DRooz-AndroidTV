package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.CategoryKind
import com.alikarami.drooz.site.model.SiteCategory
import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryMenuParserTest {
    private val home = CategoryMenuParser.parse(fixture("home.html"))

    private fun paths(kind: CategoryKind) = home.filter { it.kind == kind }.map { it.path }

    @Test fun filmCategoriesInMenuOrder() {
        assertEquals(
            listOf(
                "/archive/category/new-films/",
                "/archive/category/featured-films/",
                "/archive/category/stream/?type=film",
                "/archive/film/genre/animation/",
                "/archive/category/anime/?type=film",
                "/archive/category/3d-films/",
                "/archive/film/country/iran/",
                "/archive/category/old-persian/",
                "/archive/category/dubbed-films/",
                "/archive/category/subbed-films/",
                "/archive/category/safe-for-children/",
            ),
            paths(CategoryKind.FILM),
        )
    }

    @Test fun seriesCategoriesInMenuOrder() {
        assertEquals(
            listOf(
                "/archive/category/tv-mini-series/",
                "/archive/category/new-tv-show/",
                "/archive/series/",
                "/archive/category/finished-series/",
                "/archive/category/stream/?type=series",
                "/archive/category/anime/?type=series",
                "/archive/series/country/iran/",
                "/archive/category/dubbed-series/",
                "/archive/category/subbed-series/",
            ),
            paths(CategoryKind.SERIES),
        )
        assertTrue(home.none { it.kind == CategoryKind.BOTH })
    }

    @Test fun keepsMenuLabels() {
        assertTrue(SiteCategory("سریال های بروز شده", "/archive/series/", CategoryKind.SERIES) in home)
        assertTrue(SiteCategory("فیلم های انیمه", "/archive/category/anime/?type=film", CategoryKind.FILM) in home)
        assertTrue(SiteCategory("مناسب کودکان", "/archive/category/safe-for-children/", CategoryKind.FILM) in home)
    }

    @Test fun excludesNonListings() {
        val all = home.map { it.path }
        // Playlists (no fixture proves their markup), coming-soon (not a normal listing), static pages, search.
        assertTrue(all.none { it.startsWith("/archive/playlist/") })
        assertTrue("/archive/category/coming-soon/" !in all)
        assertTrue(all.none { it.startsWith("/page/") || it.startsWith("/user/") || it.contains("advancedSearch") })
        assertEquals(all.distinct(), all)
    }

    @Test fun listPagesCarryTheSameMenu() {
        assertEquals(home, CategoryMenuParser.parse(fixture("list_new_films.html")))
    }

    @Test fun noMenuGivesNothing() {
        assertEquals(
            emptyList<SiteCategory>(),
            CategoryMenuParser.parse("<html><body><a href='/archive/series/'>x</a></body></html>"),
        )
    }

    @Test fun classifiesByUrlBeforeTheDropdown() {
        val html = """
            <nav><div id="navbar"><ul>
              <li class="nav-item dropdown"><a class="nav-link dropdown-toggle" href="#">فیلم ها</a>
                <div class="dropdown-menu">
                  <a class="dropdown-item" href="/archive/category/anime/?type=series">انیمه</a>
                  <a class="dropdown-item" href="https://robin.robert-redford.net/archive/series/country/iran/">ایرانی</a>
                  <a class="dropdown-item" href="/archive/category/dubbed-series">دوبله</a>
                  <a class="dropdown-item" href="/archive/category/kids/">کودک</a>
                  <a class="dropdown-item" href="/archive/category/kids/">کودک دوباره</a>
                  <a class="dropdown-item" href="/archive/category/new-films/?sort=x">مرتب</a>
                </div></li>
              <li class="nav-item dropdown"><a class="nav-link dropdown-toggle" href="#">سایر</a>
                <div class="dropdown-menu">
                  <a class="dropdown-item" href="/archive/category/misc/">متفرقه</a>
                  <a class="dropdown-item" href="/archive/category/new-films/">فیلم&nbsp;های   جدید</a>
                  <a class="dropdown-item" href="/archive/category/empty/"> </a>
                </div></li>
            </ul></div></nav>
        """.trimIndent()
        assertEquals(
            listOf(
                SiteCategory("انیمه", "/archive/category/anime/?type=series", CategoryKind.SERIES),
                SiteCategory("ایرانی", "/archive/series/country/iran/", CategoryKind.SERIES),
                SiteCategory("دوبله", "/archive/category/dubbed-series/", CategoryKind.SERIES),
                SiteCategory("کودک", "/archive/category/kids/", CategoryKind.FILM),
                SiteCategory("متفرقه", "/archive/category/misc/", CategoryKind.BOTH),
                SiteCategory("فیلم های جدید", "/archive/category/new-films/", CategoryKind.FILM),
            ),
            CategoryMenuParser.parse(html),
        )
    }
}
