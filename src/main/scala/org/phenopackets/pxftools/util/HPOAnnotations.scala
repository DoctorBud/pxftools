package org.phenopackets.pxftools.util

import java.io.InputStream
import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.phenopackets.api.PhenoPacket
import org.phenopackets.api.io.RdfReader
import org.phenopackets.api.util.ContextUtil
import org.phenopackets.pxftools.util.PhenoPacketVocabulary._
import org.phenoscape.scowl._
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.AxiomType
import org.semanticweb.owlapi.model.IRI

import com.github.jsonldjava.core.Context
import com.github.tototoshi.csv.CSVReader
import com.github.tototoshi.csv.TSVFormat
import com.hp.hpl.jena.rdf.model.ModelFactory
import com.hp.hpl.jena.rdf.model.Resource
import com.hp.hpl.jena.rdf.model.ResourceFactory
import com.hp.hpl.jena.rdf.model.Statement
import com.hp.hpl.jena.vocabulary.RDF
import com.hp.hpl.jena.vocabulary.RDFS
import com.typesafe.scalalogging.LazyLogging

object HPOAnnotations extends LazyLogging {

  def read(stream: InputStream): PhenoPacket = importFromTable(CSVReader.open(scala.io.Source.fromInputStream(stream, "utf-8"))(new TSVFormat {}))

  def importFromTable(table: CSVReader): PhenoPacket = {
    val packetURI = s"urn:uuid:${UUID.randomUUID.toString}"
    val packet = ResourceFactory.createResource(packetURI)
    val triples = table.iteratorWithHeaders.flatMap(rowToTriples(_, packet)).toSeq
    val model = ModelFactory.createDefaultModel()
    model.add(triples.asJava)
    RdfReader.readModel(model, packetURI)
  }

  private def rowToTriples(row: Map[String, String], packet: Resource): Set[Statement] = {
    val statements = mutable.Set.empty[Statement]
    row.getOpt("Disease ID").foreach { diseaseID =>
      //FIXME this next line causes a pause the first time through
      val disease = ResourceFactory.createResource(ContextUtil.expandIdentifierAsValue(diseaseID.trim, HPOContext))
      statements += ResourceFactory.createStatement(packet, Diseases, disease)
      row.getOpt("Disease Name").foreach { diseaseLabel =>
        statements += ResourceFactory.createStatement(disease, RDFS.label, ResourceFactory.createTypedLiteral(diseaseLabel.trim))
      }
      val association = ResourceFactory.createResource()
      statements += ResourceFactory.createStatement(packet, PhenotypeProfile, association)
      statements += ResourceFactory.createStatement(association, Entity, disease)
      val phenotype = ResourceFactory.createResource()
      statements += ResourceFactory.createStatement(association, Phenotype, phenotype)
      row.getOpt("Phenotype ID").foreach { phenotypeID =>
        val phenotypeType = ResourceFactory.createResource(ContextUtil.expandIdentifierAsValue(phenotypeID.trim, HPOContext))
        val phenoRelation = if (row.getOpt("Negation ID").exists(_.trim.toUpperCase == "NOT")) {
          OWLComplementOf
        } else RDF.`type`
        statements += ResourceFactory.createStatement(phenotype, phenoRelation, phenotypeType)
        row.getOpt("Phenotype Name").foreach { phenotypeLabel =>
          statements += ResourceFactory.createStatement(phenotypeType, RDFS.label, ResourceFactory.createTypedLiteral(phenotypeLabel.trim))
        }
      }
      row.getOpt("Age of Onset ID").foreach { onsetID =>
        val onsetType = ResourceFactory.createResource(ContextUtil.expandIdentifierAsValue(onsetID.trim, HPOContext))
        val onset = ResourceFactory.createResource()
        statements += ResourceFactory.createStatement(phenotype, Onset, onset)
        statements += ResourceFactory.createStatement(onset, RDF.`type`, onsetType)
        row.getOpt("Age of Onset Name").foreach { onsetLabel =>
          statements += ResourceFactory.createStatement(onsetType, RDFS.label, ResourceFactory.createTypedLiteral(onsetLabel.trim))
        }
      }
      row.getOpt("Frequency").foreach { frequencyDesc =>
        val frequency = ResourceFactory.createResource()
        statements += ResourceFactory.createStatement(phenotype, Frequency, frequency)
        statements += ResourceFactory.createStatement(frequency, Description, ResourceFactory.createTypedLiteral(frequencyDesc.trim))
      }
      row.getOpt("Description").foreach { description =>
        statements += ResourceFactory.createStatement(phenotype, Description, ResourceFactory.createTypedLiteral(description.trim))
      }
      if (row.getOpt("Evidence ID").nonEmpty || row.getOpt("Pub").nonEmpty) { //FIXME handle semicolon?
        val evidence = ResourceFactory.createResource()
        statements += ResourceFactory.createStatement(association, Evidence, evidence)
        row.getOpt("Evidence ID").foreach { evidenceID =>
          val evidenceTypeOpt = evidenceCodesToURI(evidenceID.trim)
          val evidenceType = evidenceTypeOpt.getOrElse {
            logger.warn(s"No IRI found for evidence code $evidenceID")
            ResourceFactory.createResource(evidenceID.trim)
          }
          statements += ResourceFactory.createStatement(evidence, RDF.`type`, evidenceType)
          row.getOpt("Evidence Name").foreach { evidenceName =>
            statements += ResourceFactory.createStatement(evidenceType, RDFS.label, ResourceFactory.createTypedLiteral(evidenceName.trim))
          }
        }
        row.getOpt("Pub").foreach { pubID =>
          val pub = ResourceFactory.createResource(ContextUtil.expandIdentifierAsValue(pubID.trim, HPOContext))
          statements += ResourceFactory.createStatement(evidence, Source, pub)
        }
      }
    }
    statements.toSet
  }

  private val HPOContext: Context = new Context().parse(Map[String, Object](
    "obo" -> "http://purl.obolibrary.org/obo/",
    "HP" -> "obo:HP_",
    "OMIM" -> "obo:OMIM_").asJava)

  //FIXME add in appropriate IRIs once these are available
  private val knownEvidenceCodes: Map[String, Resource] = Map(
    //    "ICE" -> ResourceFactory.createResource(""),
    "IEA" -> ResourceFactory.createResource("http://purl.obolibrary.org/obo/ECO_0000501"),
    //    "ITM" -> ResourceFactory.createResource(""),
    //    "PCS" -> ResourceFactory.createResource(""),
    //    "TAE" -> ResourceFactory.createResource(""),
    //    "TEA" -> ResourceFactory.createResource(""),
    "TAS" -> ResourceFactory.createResource("http://purl.obolibrary.org/obo/ECO_0000304"))

  /**
   * HPO annotations use shorthand labels as evidence IDs
   */
  private def evidenceCodesToURI(code: String): Option[Resource] = {
    // Avoiding loading of ECO for now since most of the evidence codes in the data are not there
    // knownEvidenceCodes.get(code).orElse(evidenceCodesToURIFromECO.get(code))
    knownEvidenceCodes.get(code)
  }

  private lazy val evidenceCodesToURIFromECO: Map[String, Resource] = {
    logger.info("Downloading ECO to look for evidence code mappings.")
    val manager = OWLManager.createOWLOntologyManager()
    val eco = manager.loadOntology(IRI.create("http://purl.obolibrary.org/obo/eco.owl"))
    val HasExactSynonym = AnnotationProperty("http://www.geneontology.org/formats/oboInOwl#hasExactSynonym")
    (for {
      AnnotationAssertion(_, HasExactSynonym, term: IRI, synonym ^^ dt) <- eco.getAxioms(AxiomType.ANNOTATION_ASSERTION).asScala
    } yield {
      synonym -> ResourceFactory.createResource(term.toString)
    }).toMap
  }

  private implicit class NullEmptyStringMap(val self: Map[String, String]) extends AnyVal {

    //scala-csv puts empty strings in the result map; convert to None instead
    def getOpt(key: String): Option[String] = self.get(key).filter(_.nonEmpty)

  }

}
