package com.graphhopper.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.shapes.GHPoint;
import com.github.javafaker.Faker;

/**
 * Suite de tests (7) qui vise à valider le comportement de la classe Router
 * face à différentes requêtes de routage potentiellement invalides.
 * 
 * les objectifs sont les suivants:
 * - augmenter le score de mutation en testant les conditions limites
 * - détecter les mutants liés aux comparaisons, bornes et validations
 * 
 * classes ciblées: Router (validation des requêtes)
 * mutations visées: conditions inversées, bornes modifiées, 
 * comparateurs changés (<, <=, >, >=), constantes altérées
 */
public class RouterRequestTest {

    /**
     * crée une instance de Router avec un graphe minimal contenant
     * 2 noeuds pour définir les limites géographiques (bounding box)
     * Cette méthode utilitaire permet de tester la validation sans
     * avoir besoin d'un graphe routier complet
     */
    private Router createRouterWithGeographicBounds(double minLat, double minLon, double maxLat, double maxLon) {
        BaseGraph graph = new BaseGraph.Builder((byte) 0).create();
        NodeAccess nodeAccess = graph.getNodeAccess();
        
        // Définition des limites via 2 noeuds
        nodeAccess.setNode(0, minLat, minLon);
        nodeAccess.setNode(1, maxLat, maxLon);

        return new Router(
                graph,
                null,
                null,
                Collections.emptyMap(),
                null,
                null,
                null,
                null,
                Collections.emptyMap(),
                Collections.emptyMap());
    }

    /**
     * Test 1: validateEmptyRequestWithoutPoints
     * 
     * Intention: vérifier que Router rejette proprement une requête
     * 
     * Motivation des données: Une GHRequest sans points représente
     * le cas minimal d'entrée invalide. Ce test vérifie la validation
     * la plus basique.
     * 
     * Oracle: la réponse doit contenir des erreurs et le message
     * d'erreur doit explicitement mentionner l'absence de points
     */
    @Test
    void validateEmptyRequestWithoutPoints() {
        Router router = createRouterWithGeographicBounds(-10, -10, 10, 10);
        GHRequest emptyReq = new GHRequest();
        
        GHResponse res = router.route(emptyReq);
        
        assertTrue(res.hasErrors(), "Une requête sans points doit être rejetée");
        String errorMsg = res.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("point"), 
                "Le message d'erreur doit mentionner l'absence de points");
    }

    /**
     * Test 2: detectNullElementInPointsList
     * 
     * Intention: Tester la détection d'éléments null dans la liste
     * des points de passage, avec identification de la position.
     * 
     * Motivation des données: Liste avec 3 points dont le 2ième
     * est null. Ce placement au milieu teste la capacité à détecter
     * les nulls à n'importe quelle position, pas seulement au début/fin.
     * 
     * Oracle: Router doit rejeter la requête avec une erreur mentionnant
     * explicitement le point null et idéalement sa position (index 1).
     */
    @Test
    void detectNullElementInPointsList() {
        Router router = createRouterWithGeographicBounds(-5, -5, 5, 5);
        
        List<GHPoint> nullPoints = new ArrayList<>();
        nullPoints.add(new GHPoint(0.0, 0.0));
        nullPoints.add(null); // Position 1 - null au milieu
        nullPoints.add(new GHPoint(1.0, 1.0));
        
        GHRequest request = new GHRequest(nullPoints);
        GHResponse res = router.route(request);
        
        assertTrue(res.hasErrors(), "Une liste avec point null doit être rejetée");
        String errorMsg = res.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("null") && errorMsg.contains("point"),
                "L'erreur doit indiquer qu'un point est null");
    }

    /**
     * Test 3: rejectPointsOutsideGraphBoundaries
     * 
     * Intention: Vérifier que les points en dehors des limites
     * géographiques du graphe sont correctement détectés et rejetés.
     * 
     * Motivation des données: Graphe avec bounds [-2, 2], points à
     * (0, 0) valide et (50, 50) largement hors limites pour éviter
     * les cas limites de précision flottante.
     * 
     * Oracle: La requête doit échouer avec un message mentionnant
     * les limites (bounds) dépassées
     */
    @Test
    void rejectPointsOutsideGraphBoundaries() {
        Router router = createRouterWithGeographicBounds(-2, -2, 2, 2);
        
        GHRequest req = new GHRequest(Arrays.asList(
                new GHPoint(0.0, 0.0),      // Dans les limites
                new GHPoint(50.0, 50.0)      // Hors limites
        ));
        
        GHResponse res = router.route(req);
        
        assertTrue(res.hasErrors(), "Points hors limites doivent être rejetés");
        String errorMsg = res.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("bound") || errorMsg.contains("outside"),
                "L'erreur doit mentionner le dépassement des limites");
    }

    /**
     * Test 4: validateHeadingCountConsistency
     * 
     * Intention: Tester que le nombre de headings doit correspondre
     * au nombre de points (ou être 0, 1, ou égal au nombre de points).
     * 
     * Motivation des données: 3 points avec 2 headings pour créer
     * un décalage qui viole la règle de cohérence des tailles.
     * 
     * Oracle: La requête doit être rejetée avec une erreur explicite
     * sur l'incohérence entre headings et points
     */
    @Test
    void validateHeadingCountConsistency() {
        Router router = createRouterWithGeographicBounds(-5, -5, 5, 5);
        
        GHRequest req = new GHRequest(Arrays.asList(
                new GHPoint(0.0, 0.0),
                new GHPoint(1.0, 1.0),
                new GHPoint(2.0, 2.0)
        ));
        
        // 2 headings pour 3 points - incohérent
        req.setHeadings(Arrays.asList(45.0, 90.0));
        
        GHResponse res = router.route(req);
        
        assertTrue(res.hasErrors(), "Nombre incohérent de headings doit être rejeté");
        String errorMsg = res.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("heading"),
                "L'erreur doit mentionner le problème de headings");
    }

    /**
     * Test 5: enforceAzimuthRangeForHeadings
     * 
     * Intention: Vérifier que les headings doivent être dans [0, 360)
     * ou NaN, et que les valeurs négatives sont rejetées.
     * 
     * Motivation des données: Heading de -45 degrés qui est une valeur
     * courante mais invalide (devrait être 315). Test aussi 400 degrés
     * pour vérifier la borne supérieure.
     * 
     * Oracle: Les headings hors de (0, 360) doivent déclencher une erreur
     * mentionnant la plage valide ou la valeur invalide.
     */
    @Test
    void enforceAzimuthRangeForHeadings() {
        Router router = createRouterWithGeographicBounds(-5, -5, 5, 5);
        
        // Test avec heading négatif
        GHRequest negativeReq = new GHRequest(Arrays.asList(
                new GHPoint(0.0, 0.0),
                new GHPoint(1.0, 1.0)
        ));
        negativeReq.setHeadings(Arrays.asList(-45.0, 90.0));
        
        GHResponse negativeResp = router.route(negativeReq);
        assertTrue(negativeResp.hasErrors(), "Heading négatif doit être rejeté");
        
        // Test avec heading > 360
        GHRequest reqTooLarge = new GHRequest(Arrays.asList(
                new GHPoint(0.0, 0.0),
                new GHPoint(1.0, 1.0)
        ));
        reqTooLarge.setHeadings(Arrays.asList(90.0, 400.0));
        
        GHResponse responseTooLarge = router.route(reqTooLarge);
        assertTrue(responseTooLarge.hasErrors(), "Heading > 360 doit être rejeté");
        
        String errorMsg = responseTooLarge.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("heading") || errorMsg.contains("azimuth"),
                "L'erreur doit mentionner le problème de heading/azimuth");
    }

    /**
     * Test 6: validateCurbsideOptionsWithFaker
     * 
     * Intention: Utiliser Faker pour générer aléatoirement des combinaisons
     * de curbsides invalides (nombre incorrect) et vérifier le rejet.
     * 
     * Motivation des données: Faker génère entre 3 et 6 points avec des
     * coordonnées aléatoires valides, mais seulement N-1 curbsides pour
     * forcer une incohérence. Seed fixe (12345) pour reproduire le même résultat.
     * 
     * Oracle: Le décalage entre nombre de points et curbsides doit
     * systématiquement déclencher une erreur mentionnant "curbside".
     */
    @Test
    void validateCurbsideOptionsWithFaker() {
        Router router = createRouterWithGeographicBounds(-10, -10, 10, 10);
        Faker faker = new Faker(new Random(12345));
        
        // Génération aléatoire de 3 à 6 points
        int pointCount = faker.number().numberBetween(3, 7);
        List<GHPoint> points = new ArrayList<>();
        
        for (int i = 0; i < pointCount; i++) {
            double lat = faker.number().randomDouble(4, -8, 8);
            double lon = faker.number().randomDouble(4, -8, 8);
            points.add(new GHPoint(lat, lon));
        }
        
        // Génération de seulement pointCount-1 curbsides (mismatch intentionnel)
        List<String> curbsides = new ArrayList<>();
        String[] validCurbsides = {"left", "right", "any"};
        
        for (int i = 0; i < pointCount - 1; i++) {
            curbsides.add(faker.options().option(validCurbsides));
        }
        
        GHRequest request = new GHRequest(points);
        request.setCurbsides(curbsides);
        
        GHResponse response = router.route(request);
        
        assertTrue(response.hasErrors(),
                String.format("Mismatch curbsides: %d curbsides pour %d points", 
                    curbsides.size(), points.size()));
        
        String errorMsg = response.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("curbside"),
                "L'erreur doit mentionner le problème de curbsides");
    }

    /**
     * Test 7: verifyPointHintValidationWithRandomData
     * 
     * Intention: Tester la validation des point hints avec des données
     * générées aléatoirement, incluant des hints vides et trop nombreux.
     * 
     * Motivation des données: Utilisation de Faker pour générer des
     * noms de rues réalistes comme hints, avec intentionnellement
     * plus de hints que de points pour tester la validation de taille.
     * Test aussi le cas inverse (moins de hints que de points).
     * 
     * Oracle: Toute incohérence entre le nombre de hints et de points
     * doit déclencher une erreur mentionnant "hint" ou "point hint".
     */
    @Test
    void verifyPointHintValidationWithRandomData() {
        Router router = createRouterWithGeographicBounds(-10, -10, 10, 10);
        Faker faker = new Faker(new Random(67890)); // Seed différent
        
        // Cas 1: Plus de hints que de points
        List<GHPoint> points = Arrays.asList(
                new GHPoint(0.0, 0.0),
                new GHPoint(1.0, 1.0)
        );
        
        List<String> tooManyHints = IntStream.range(0, 4)
                .mapToObj(i -> faker.address().streetName())
                .collect(Collectors.toList());
        
        GHRequest reqTooMany = new GHRequest(points);
        reqTooMany.setPointHints(tooManyHints);
        
        GHResponse resTooMany = router.route(reqTooMany);
        assertTrue(resTooMany.hasErrors(),
                "Trop de hints (4) pour 2 points doit être rejeté");
        
        // Cas 2: Moins de hints que de points (mais > 1)
        List<GHPoint> manyPoints = IntStream.range(0, 5)
                .mapToObj(i -> new GHPoint(
                    faker.number().randomDouble(3, -5, 5),
                    faker.number().randomDouble(3, -5, 5)
                ))
                .collect(Collectors.toList());
        
        List<String> tooFewHints = Arrays.asList(
                faker.address().streetName(),
                faker.address().streetName()
        );
        
        GHRequest reqTooFew = new GHRequest(manyPoints);
        reqTooFew.setPointHints(tooFewHints);
        
        GHResponse resTooFew = router.route(reqTooFew);
        assertTrue(resTooFew.hasErrors(),
                "2 hints pour 5 points doit être rejeté");
        
        String errorMsg = resTooFew.getErrors().get(0).getMessage().toLowerCase();
        assertTrue(errorMsg.contains("hint"),
                "L'erreur doit mentionner le problème de hints");
    }
}