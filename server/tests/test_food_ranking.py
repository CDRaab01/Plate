"""Pure relevance-ranking tests (app/foods/ranking.py) — no DB, no network."""

from dataclasses import dataclass

from app.foods.ranking import rank_foods, relevance_score


def test_exact_name_beats_prefix_beats_word_match_beats_mention():
    q = "black olives"
    exact = relevance_score("Black olives", q)
    prefix = relevance_score("Black olives, canned", q)
    words = relevance_score("Ripe black olives, sliced", q)
    mention = relevance_score(
        "Creme fraiche sauce, pulled pork, feta, black olives and banana peppers on a pizza crust",
        q,
    )
    assert exact > prefix > words >= mention
    assert exact == 100.0


def test_word_order_insensitive():
    # "Turkey, ground" satisfies the query "ground turkey" (both words present).
    assert relevance_score("Turkey, ground, raw", "ground turkey") == 60.0


def test_no_overlap_scores_low():
    assert relevance_score("Cheddar cheese", "black olives") == 0.0


def test_blank_query_is_zero():
    assert relevance_score("Anything", "   ") == 0.0


@dataclass
class _F:
    id: int
    name: str


def test_rank_orders_by_relevance_then_length_then_name():
    foods = [
        _F(1, "Creme fraiche pizza with black olives and banana peppers on a gyro crust"),
        _F(2, "Black olives"),
        _F(3, "Black olives, large"),
    ]
    ranked = [f.id for f in rank_foods(foods, "black olives")]
    assert ranked == [2, 3, 1]  # exact, then word-match (shorter), then the long mention


def test_recently_logged_food_stays_on_top():
    foods = [_F(1, "Black olives"), _F(2, "Kalamata olives brine snack")]
    # Food 2 is a weaker match but was logged most recently → it leads.
    ranked = [f.id for f in rank_foods(foods, "olives", recent_rank={2: 0})]
    assert ranked[0] == 2


def test_similarity_blend_ranks_fuzzy_match():
    # "chiken brest" shares no whole tokens with either name; the trgm similarity map (as the
    # DB would supply it) is what lifts the real food above an unrelated one.
    foods = [_F(1, "Cheddar cheese"), _F(2, "Chicken breast, raw")]
    ranked = [f.id for f in rank_foods(foods, "chiken brest", similarity={2: 0.55})]
    assert ranked[0] == 2


def test_similarity_never_beats_exact_name_match():
    foods = [_F(1, "Chicken breast"), _F(2, "Chicken breast, raw")]
    # Even a perfect similarity (60 after weighting) can't outrank the exact match (100).
    ranked = [f.id for f in rank_foods(foods, "chicken breast", similarity={2: 1.0})]
    assert ranked[0] == 1


@dataclass
class _FlaggedFood:
    id: int
    name: str
    macros_incomplete: bool = False


def test_incomplete_macros_rank_below_complete_at_equal_score():
    foods = [
        _FlaggedFood(1, "Black olives", macros_incomplete=True),
        _FlaggedFood(2, "Black olives"),
    ]
    ranked = [f.id for f in rank_foods(foods, "black olives")]
    assert ranked == [2, 1]
