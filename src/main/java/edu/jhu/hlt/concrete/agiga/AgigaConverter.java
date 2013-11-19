package edu.jhu.hlt.concrete.agiga;

import edu.jhu.agiga.*;
import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Parse;
import edu.jhu.hlt.concrete.SectionSegmentation;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SentenceSegmentation;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.stanford.nlp.trees.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.zip.GZIPOutputStream;
import java.io.*;

public class AgigaConverter {

	public static final String toolName = "Annotated Gigaword Pipeline";
	public static final String corpusName = "Annotated Gigaword";
	public static final long annotationTime = Calendar.getInstance().getTimeInMillis() / 1000;

	public static AnnotationMetadata metadata() { return metadata(null); }
	public static AnnotationMetadata metadata(String addToToolName) {
		String fullToolName = toolName;
		if(addToToolName != null) fullToolName += addToToolName;
		
		AnnotationMetadata md = new AnnotationMetadata();
		md.tool = fullToolName;
		md.timestamp = annotationTime;
		md.confidence = 1f;
		return md;
	}

	public static String flattenText(AgigaDocument doc) {
		StringBuilder sb = new StringBuilder();
		for(AgigaSentence sent : doc.getSents()) {
			sb.append(flattenText(sent));
			sb.append("\n");
		}
		return sb.toString();
	}

	public static String flattenText(AgigaSentence sent) {
		StringBuilder sb = new StringBuilder();
		for(AgigaToken tok : sent.getTokens())
			sb.append(tok.getWord() + " ");
		return sb.toString().trim();
	}

	public static Parse stanford2concrete(Tree root, UUID tokenizationUUID) {
		int[] nodeCounter = new int[]{0};
		int left = 0;
		int right = root.getLeaves().size();
		/*
		// this was a bug in stanford nlp;
		// if you have a terminal with a space in it, like (CD 2 1/2)
		// stanford's getLeaves() will return Trees for 2 and 1/2
		// whereas the tokenization will have one token for 2 1/2
		// => this has since been handled in agiga, but this is a check to
		//    make sure you have the right jar
		if(root.getLeaves().size() != tokenization.getTokenList().size()) {
			System.out.println("Tokenization length = " + tokenization.getTokenList().size());
			System.out.println("Parse #leaves = " + root.getLeaves().size());
			System.out.println("tokens = " + tokenization.getTokenList());
			System.out.println("leaves = " + root.getLeaves());
			System.out.println("make sure you have the newest version of agiga!");
			throw new RuntimeException("Tokenization vs Parse error! Make sure you have the newest agiga");
		}
		*/
		Parse p = new Parse();
		p.uuid = 
		return Parse.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata(" http://www.aclweb.org/anthology-new/D/D10/D10-1002.pdf"))
			.setRoot(s2cHelper(root, nodeCounter, left, right, tokenizationUUID))
			.build();
	}

	/**
	 * i'm using int[] as a java hack for int* (pass by reference rather than value).
	 */
	private static final HeadFinder HEAD_FINDER = new SemanticHeadFinder();
	private static Parse.Constituent.Builder s2cHelper(Tree root, int[] nodeCounter, int left, int right, UUID tokenizationUUID) {
		assert(nodeCounter.length == 1);
		Parse.Constituent.Builder cb = Parse.Constituent.newBuilder()
			.setId(nodeCounter[0]++)
			.setTag(root.value())
			.setTokenSequence(extractTokenRefSequence(left, right, null, tokenizationUUID));

		Tree headTree = root.isLeaf() ? null : HEAD_FINDER.determineHead(root);
		int i = 0, headTreeIdx = -1;

		int leftPtr = left;
		for(Tree child : root.getChildrenAsList()) {
			int width = child.getLeaves().size();
			cb.addChild(s2cHelper(child, nodeCounter, leftPtr, leftPtr + width, tokenizationUUID));
			leftPtr += width;
			if(headTree != null && child == headTree) {
				assert(headTreeIdx < 0);
				headTreeIdx = i;
			}
			i++;
		}

		if(headTreeIdx >= 0)
			cb.setHeadChildIndex(headTreeIdx);

		return cb;
	}

	public static TokenRefSequence extractTokenRefSequence(AgigaMention m, UUID tokenizationUUID) {
		return extractTokenRefSequence(m.getStartTokenIdx(), m.getEndTokenIdx(), m.getHeadTokenIdx(), tokenizationUUID);
	}
	public static TokenRefSequence extractTokenRefSequence(int left, int right, Integer head, UUID tokenizationUUID) {
		TokenRefSequence.Builder tb = TokenRefSequence.newBuilder()
			.setTokenizationId(tokenizationUUID);
		for(int tid=left; tid<right; tid++) {
			tb.addTokenIndex(tid);
			if(head != null && head == tid) {
				tb.setAnchorTokenIndex(tid);
			}
		}
		return tb.build();
	}

	/**
	 * name is the type of dependencies, e.g. "col-deps" or "col-ccproc-deps"
	 */
	public static DependencyParse convertDependencyParse(List<AgigaTypedDependency> deps, String name) {
		DependencyParse.Builder db = DependencyParse.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata(" " + name + " http://nlp.stanford.edu/software/dependencies_manual.pdf"));
		for(AgigaTypedDependency ad : deps) {
			
			DependencyParse.Dependency.Builder depB = DependencyParse.Dependency.newBuilder()
				.setDep(ad.getDepIdx())
				.setEdgeType(ad.getType());

			if(ad.getGovIdx() >= 0)	// else ROOT
				depB.setGov(ad.getGovIdx());

			db.addDependency(depB.build());
		}
		return db.build();
	}

	public static Tokenization convertTokenization(AgigaSentence sent) {

		TokenTagging.Builder lemmaBuilder = TokenTagging.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());

		TokenTagging.Builder posBuilder = TokenTagging.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());

		TokenTagging.Builder nerBuilder = TokenTagging.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());

		//TokenTagging.Builder normNerBuilder = TokenTagging.newBuilder()
		//	.setUuid(IdUtil.generateUUID())
		//	.setMetadata(metadata());

		UUID tokenizationUUID = IdUtil.generateUUID();
		Tokenization.Builder tb = Tokenization.newBuilder()
			.setUuid(tokenizationUUID)
			.setMetadata(metadata(" http://nlp.stanford.edu/software/tokensregex.shtml"))
			.setKind(Tokenization.Kind.TOKEN_LIST);
		int charOffset = 0;
		int tokId = 0;
		for(AgigaToken tok : sent.getTokens()) {

			int curTokId = tokId++;

			// token
			tb.addToken(Token.newBuilder()
				.setTokenIndex(curTokId)
				.setText(tok.getWord())
				.setTextSpan(TextSpan.newBuilder()
					.setStart(charOffset)
					.setEnd(charOffset + tok.getWord().length())
					.build())
				.build());

			// token annotations
			lemmaBuilder.addTaggedToken(makeTaggedToken(tok.getLemma(), curTokId));
			posBuilder.addTaggedToken(makeTaggedToken(tok.getPosTag(), curTokId));
			nerBuilder.addTaggedToken(makeTaggedToken(tok.getNerTag(), curTokId));
			//normNerBuilder.addTaggedToken(makeTaggedToken(tok.getNormNerTag(), curTokId));

			charOffset += tok.getWord().length() + 1;
		}
		return tb
			.addLemmas(lemmaBuilder.build())
			.addPosTags(posBuilder.build())
			.addNerTags(nerBuilder.build())
			.addParse(stanford2concrete(sent.getStanfordContituencyTree(), tokenizationUUID))
			.addDependencyParse(convertDependencyParse(sent.getBasicDeps(), "basic-deps"))
			.addDependencyParse(convertDependencyParse(sent.getColDeps(), "col-deps"))
			.addDependencyParse(convertDependencyParse(sent.getColCcprocDeps(), "col-ccproc-deps"))
			.build();
	}

	public static TaggedToken makeTaggedToken(String tag, int tokId) {
		return TaggedToken.newBuilder()
			.setTokenIndex(tokId)
			.setTag(tag)
			.setConfidence(1f)
			.build();
	}

	public static Sentence convertSentence(AgigaSentence sent, int charsFromStartOfCommunication, List<Tokenization> addTo) {
		Tokenization tokenization = convertTokenization(sent);
		addTo.add(tokenization);	// one tokenization per sentence
		return Sentence.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setTextSpan(TextSpan.newBuilder()
				.setStart(charsFromStartOfCommunication)
				.setEnd(charsFromStartOfCommunication + flattenText(sent).length())
				.build())
			.addTokenization(tokenization)
			.build();
	}

	public static SentenceSegmentation sentenceSegment(AgigaDocument doc, List<Tokenization> addTo) {
		SentenceSegmentation.Builder sb = SentenceSegmentation.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata(" Splitta http://www.aclweb.org/anthology-new/N/N09/N09-2061.pdf"));
		int charsFromStartOfCommunication = 0;	// communication only has one section
		for(AgigaSentence sentence : doc.getSents()) {
			sb = sb.addSentence(convertSentence(sentence, charsFromStartOfCommunication, addTo));
			charsFromStartOfCommunication += flattenText(sentence).length() + 1;	// +1 for newline at end of sentence
		}
		return sb.build();
	}

	public static SectionSegmentation sectionSegment(AgigaDocument doc, String rawText, List<Tokenization> addTo) {
		return SectionSegmentation.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata())
			.addSection(Section.newBuilder()
				.setUuid(IdUtil.generateUUID())
				.setKind(Section.Kind.PASSAGE)
				.setTextSpan(TextSpan.newBuilder()
					.setStart(0)
					.setEnd(rawText.length())
					.build())
				.addSentenceSegmentation(sentenceSegment(doc, addTo))
				.build())
			.build();
	}

	public static String extractMentionString(AgigaMention m, AgigaDocument doc) {
		List<AgigaToken> sentence = doc.getSents().get(m.getSentenceIdx()).getTokens();
		StringBuilder sb = new StringBuilder();
		for(int i=m.getStartTokenIdx(); i<m.getEndTokenIdx(); i++) {
			sb.append(sentence.get(i).getWord());
			if(i < m.getEndTokenIdx()-1)
				sb.append(" ");
		}
		return sb.toString();
	}

	public static EntityMention convertMention(AgigaMention m, AgigaDocument doc,
			edu.jhu.hlt.concrete.Concrete.UUID corefSet, Tokenization tokenization) {
		String mstring = extractMentionString(m, doc);
		return EntityMention.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setTokens(extractTokenRefSequence(m, tokenization.getUuid()))
			.setEntityType(Entity.Type.UNKNOWN)
			.setPhraseType(EntityMention.PhraseType.NAME)	// TODO warn users that this may not be accurate
			.setConfidence(1f)
			.setText(mstring)		// TODO merge this an method below
			.build();
	}

	/**
	 * adds EntityMentions to EnityMentionSet.Builder
	 * creates and returns an Entity
	 */
	public static Entity convertCoref(EntityMentionSet.Builder emsb, AgigaCoref coref, AgigaDocument doc, List<Tokenization> toks) {
		Entity.Builder entBuilder = Entity.newBuilder()
			.setUuid(IdUtil.generateUUID());
		for(AgigaMention m : coref.getMentions()) {
			EntityMention em = convertMention(m, doc, IdUtil.generateUUID(), toks.get(m.getSentenceIdx()));
			emsb.addMention(em);
			entBuilder.addMentionId(em.getUuid());
		}
		return entBuilder.build();
	}

	public static Communication convertDoc(AgigaDocument doc) {
		CommunicationGUID guid = CommunicationGUID.newBuilder()
			.setCorpusName(corpusName)
			.setCommunicationId(doc.getDocId())
			.build();
		String flatText = flattenText(doc);
		List<Tokenization> toks = new ArrayList<Tokenization>();
		Communication.Builder cb = Communication.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setGuid(guid)
			.setText(flatText)
			.addSectionSegmentation(sectionSegment(doc, flatText, toks))
			.setKind(Communication.Kind.NEWS);
		// this must occur last so that the tokenizations have been added to toks
		EntityMentionSet.Builder emsb = EntityMentionSet.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata(" http://nlp.stanford.edu/pubs/conllst2011-coref.pdf"));
		EntitySet.Builder esb = EntitySet.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata(" http://nlp.stanford.edu/pubs/conllst2011-coref.pdf"));
		for(AgigaCoref coref : doc.getCorefs()) {
			Entity e = convertCoref(emsb, coref, doc, toks);
			esb.addEntity(e);
		}
		cb.addEntityMentionSet(emsb);
		cb.addEntitySet(esb);
		cb.setUuid(IdUtil.generateUUID());
		return cb.build();
	}


	public static void main(String[] args) throws Exception {
		if(args.length < 2) {
			System.out.println("please provide:");
			System.out.println("1 or more input Agiga XML files");
			System.out.println("an output Concrete Protobuf file");
			return;
		}
		long start = System.currentTimeMillis();
		File output = new File(args[args.length-1]);
		ProtocolBufferWriter pbr = new ProtocolBufferWriter(
			output.getName().toLowerCase().endsWith("gz")
			? new GZIPOutputStream(new FileOutputStream(output))
			: new FileOutputStream(output));

		//BufferedOutputStream writer = new BufferedOutputStream(
		//	output.getName().toLowerCase().endsWith("gz")
		//	? new GZIPOutputStream(new FileOutputStream(output))
		//	: new FileOutputStream(output));

		int c = 0;
		int step = 250;
		for(int i=0; i<args.length-1; i++) {
			File agigaXML = new File(args[i]);	assert(agigaXML.exists() && agigaXML.isFile());
			StreamingDocumentReader docReader = new StreamingDocumentReader(agigaXML.getPath(), new AgigaPrefs());
			System.out.println("reading from " + agigaXML.getPath());
			for(AgigaDocument doc : docReader) {
				Communication comm = convertDoc(doc);
				//comm.writeDelimitedTo(writer);
				pbr.write(comm);
				c++;
				if(c % step == 0) {
					System.out.printf("wrote %d documents in %.1f sec\n",
						c, (System.currentTimeMillis() - start)/1000d);
				}
			}
		}
		//writer.close();
		pbr.close();
		System.out.printf("done, wrote %d communications to %s in %.1f seconds\n",
			c, output.getPath(), (System.currentTimeMillis() - start)/1000d);
	}
}

