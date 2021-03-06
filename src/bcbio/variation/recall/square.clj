(ns bcbio.variation.recall.square
  "Performing squaring off of variant call sets, recalling at all sample positions.
   This converts a merged dataset with no calls at positions not assessed in the
   sample, into a fully 'square' merged callset with reference calls at positions
   without evidence for a variant, distinguishing true no-calls from reference
   calls."
  (:require [bcbio.align.bam :as bam]
            [bcbio.align.cram :as cram]
            [bcbio.align.greads :as greads]
            [bcbio.run.fsp :as fsp]
            [bcbio.run.itx :as itx]
            [bcbio.run.parallel :refer [rmap]]
            [bcbio.variation.ensemble.prep :as eprep]
            [bcbio.variation.recall.clhelp :as clhelp]
            [bcbio.variation.recall.merge :as merge]
            [bcbio.variation.recall.vcfheader :as vcfheader]
            [bcbio.variation.recall.vcfutils :as vcfutils]
            [clojure.core.strint :refer [<<]]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [me.raynes.fs :as fs]
            [version-clj.core :refer [version-compare]]))

(defn subset-sample-region
  "Subset the input file to the given region and sample."
  [vcf-file sample region out-file]
  (itx/run-cmd out-file
               "bcftools view -O ~{(vcfutils/bcftools-out-type out-file)} "
               "-r ~{(eprep/region->samstr region)} -s ~{sample} "
               "~{(eprep/bgzip-index-vcf vcf-file)} > ~{out-file}")
  (eprep/bgzip-index-vcf out-file :remove-orig? true))

(defn- intersect-variants
  "Retrieve VCF variants present in both in-file and cmp-file."
  [in-file cmp-file ref-file out-file]
  (itx/run-cmd out-file
               "vcfintersect -r ~{ref-file} -i ~{cmp-file} ~{in-file} | "
               "bgzip > ~{out-file}")
  (eprep/bgzip-index-vcf out-file :remove-orig? true))

(defn- unique-variants
  "Retrieve variants from in-file not present in cmp-file."
  [in-file cmp-file ref-file out-file]
  (itx/run-cmd out-file
               "vcfintersect -v -r ~{ref-file} -i ~{cmp-file} ~{in-file} | "
               "bgzip > ~{out-file}")
  (eprep/bgzip-index-vcf out-file :remove-orig? true))

(defmulti recall-variants
  "Recall variants only at positions in provided input VCF file, using multiple callers."
  (fn [& args]
    (keyword (get (last args) :caller :freebayes))))

(defmethod recall-variants :freebayes
  ^{:doc "Perform variant recalling at specified positions with FreeBayes.
          Cleans up FreeBayes calling:
           - Converts non-passing low quality variants to reference calls.
           - Reporting calls as no-call if they do not have at least a depth of 4
           - Ceil FreeBayes qualities at 1 to avoid errors when feeding to GATK downstream.
           - Remove duplicate alternative alleles."}
  [sample region vcf-file bam-file ref-file out-file config]
  (let [sample-file (str (fsp/file-root out-file) "-samples.txt")
        ploidy-str (if (:ploidy config) (format "-p %s" (:ploidy config)) "")
        filters ["NUMALT == 0", "%QUAL < 5", "AF[*] <= 0.5 && DP < 4",
                 "AF[*] <= 0.5 && DP < 13 && %QUAL < 10","AF[*] > 0.5 && DP < 4 && %QUAL < 50"]
        nosupport-filter "bcftools filter -S . -e 'AC == 0 && DP < 4' 2> /dev/null"
        filter_str (string/join " | " (map #(format "bcftools filter -S 0 -e 'AC > 0 && %s' 2> /dev/null" %)
                                           filters))]
    (spit sample-file sample)
    (itx/run-cmd out-file
                 "freebayes -b ~{bam-file} --variant-input ~{vcf-file} --only-use-input-alleles "
                 "--min-repeat-entropy 1 ~{ploidy-str} "
                 "--use-best-n-alleles 4 --min-mapping-quality 20 "
                 "-f ~{ref-file} -r ~{(eprep/region->freebayes region)} -s ~{sample-file}  | "
                 "vcfuniqalleles | ~{filter_str} | vcffixup - | ~{nosupport-filter} | "
                 "awk -F$'\\t' -v OFS='\\t' '{if ($0 !~ /^#/ && $6 < 1) $6 = 1 } {print}' | "
                 "bgzip -c > ~{out-file}")
    (eprep/bgzip-index-vcf out-file)))

(defmethod recall-variants :platypus
  ^{:doc "Perform variant recalling at specified positions with Platypus.
          Perform post-filtration of platypus variant calls.
          Removes hard Q20 filter and replaces with NA12878/GiaB tuned depth
          and quality based filter.
          Performs normalization and removal of duplicate alleles.
          bgzips final output."}
  [sample region vcf-file bam-file ref-file out-file config]
  (let [filters ["FR[*] <= 0.5 && TC < 4 && %QUAL < 20",
                 "FR[*] <= 0.5 && TC < 13 && %QUAL < 10",
                 "FR[*] > 0.5 && TC < 4 && %QUAL < 20"]
        nosupport-filter "bcftools filter -S . -e 'TR == 0 && TC < 4' 2> /dev/null"
        filter_str (string/join " | " (map #(format "bcftools filter -S 0 -e 'TR > 0 && %s' 2> /dev/null" %) filters))]
    (itx/run-cmd out-file
                 "platypus callVariants --bamFiles=~{bam-file} --regions=~{(eprep/region->samstr region)} "
                 "--hapScoreThreshold 10 --scThreshold 0.99 --filteredReadsFrac 0.9 "
                 "--rmsmqThreshold 20 --qdThreshold 0 --abThreshold 0.0 --minVarFreq 0.0 "
                 "--refFile=~{ref-file} --source=~{vcf-file} --minPosterior=0 --getVariantsFromBAMs=0 "
                 "--logFileName /dev/null --verbosity=1 --output - | "
                 "awk -F$'\\t' -v OFS='\\t' '{if ($0 !~ /^#/) $7 = \"PASS\" } {print}' | "
                 "vt normalize -r ~{ref-file} -q - 2> /dev/null | vcfuniqalleles | "
                 "~{filter_str} | vcffixup - | ~{nosupport-filter} | bgzip -c > ~{out-file}")
    (eprep/bgzip-index-vcf out-file)))

(defn vcf->bcftools-call-input
  "Convert a VCF input file into the custom format needed for bcftools call.
   XXX Not currently used as constrained calling is broken in samtools 1.0 for multiple positions."
  [vcf-file]
  (let [out-file (str (fsp/file-root vcf-file) "-callinput.tsv")]
    (itx/run-cmd out-file
                 "bcftools query -f '%CHROM\\t%POS\\t%REF,%ALT\\n' ~{vcf-file} > ~{out-file} ")
    (if false ;; (> (fs/size out-file) 0)
      (<< "-T ~{out-file} --constrain alleles")
      "")))

(defmethod recall-variants :samtools
  ^{:doc "Perform variant recalling at specified positions with samtools.
          XXX Curently uses normalization instead of constrained calling."}
  [sample region vcf-file bam-file ref-file out-file config]
  (let [filters ["AC[0] / AN <= 0.5 && DP < 4 && %QUAL < 20"
                 "DP < 13 && %QUAL < 10"
                 "AC[0] / AN > 0.5 && DP < 4 && %QUAL < 50"]
        filter_str (string/join " | " (map #(format "bcftools filter -e '%s' 2> /dev/null" %) filters))]
    (itx/run-cmd out-file
                 "samtools mpileup -f ~{ref-file} -t DP -u -g -r ~{(eprep/region->samstr region)} "
                 "-l ~{vcf-file} ~{bam-file} | "
                 "bcftools call -m ~{(vcf->bcftools-call-input vcf-file)} - | "
                 "bcftools norm -f ~{ref-file} -m '+both' - | "
                 "sed 's/,Version=3>/>/' | sed 's/Number=R/Number=./' | "
                 "~{filter_str} | bgzip -c > ~{out-file}")
    (eprep/bgzip-index-vcf out-file)))

(defn union-variants
  "Use GATK CombineVariants to merge multiple input files"
  [vcf-files ref-file region out-file]
  (let [variant-str (string/join " " (map #(str "--variant " (eprep/bgzip-index-vcf %)) vcf-files))]
    (itx/run-cmd out-file
                 "gatk-framework -Xms250m -Xmx~{(eprep/gatk-mem vcf-files)} -XX:+UseSerialGC "
                 "-T CombineVariants -R ~{ref-file} "
                 "-L ~{(eprep/region->samstr region)} --out ~{out-file} "
                 "--suppressCommandLineHeader --setKey null "
                 "-U LENIENT_VCF_PROCESSING --logging_level ERROR "
                 "~{variant-str}")
    (eprep/bgzip-index-vcf out-file)))

(defn- sample-by-region
  "Square off a specific sample in a genomic region, given all possible variants.
    - Subset to the current variant region.
    - Identify missing uncalled variants: create files of existing and missing variants.
    - Recall at missing positions with FreeBayes.
    - Merge original and recalled variants."
  [sample vcf-file bam-file union-vcf region ref-file out-file config]
  (let [work-dir (fsp/safe-mkdir (str (fsp/file-root out-file) "-work"))
        fnames (into {} (map (fn [x] [(keyword x) (str (io/file work-dir (format "%s.vcf.gz" x)))])
                             ["region" "existing" "needcall" "recall"]))]
    (when (itx/needs-run? out-file)
      (itx/with-temp-dir [tmp-dir (fs/parent out-file)]
        (let [region-bam-file (greads/subset-in-region bam-file ref-file region tmp-dir)]
          (subset-sample-region vcf-file sample region (:region fnames))
          (intersect-variants (:region fnames) union-vcf ref-file (:existing fnames))
          (unique-variants union-vcf (:region fnames) ref-file (:needcall fnames))
          (recall-variants sample region (:needcall fnames) region-bam-file ref-file (:recall fnames) config)
          (union-variants [(:recall fnames) (:existing fnames)] ref-file region out-file))))
    out-file))

(defn- sample-by-region-prep
  "Prepare for squaring off a sample in a region, setup out file and check conditions.
   We only can perform squaring off with a BAM file for the sample."
  [sample vcf-file bam-file union-vcf region ref-file out-dir config]
  (let [out-file (str (io/file out-dir (format "%s-%s.vcf.gz" sample (eprep/region->safestr region))))]
    (cond
     (nil? bam-file) (subset-sample-region vcf-file sample region out-file)
     (itx/needs-run? out-file) (sample-by-region sample vcf-file bam-file union-vcf region ref-file out-file config)
     :else out-file)))

(defn- get-ploidy-line
  "Parse called ploidy from a VCF genotype call"
  [line]
  (let [[format call] (string/split line #"\t")
        gt-index (.indexOf (string/split format #":") "GT")]
    (when-not (neg? gt-index)
      (-> (string/split call #":")
          (nth gt-index)
          (string/split #"[|/]")
          count))))

(defn- get-ploidy-file
  "Retrieve ploidy from a single VCF file by grepping out FORMAT lines."
  [vcf-file region]
  (let [lines (string/split
               (:out (sh "bash" "-c"
                         (<< "bcftools view ~{vcf-file} -r ~{(eprep/region->samstr region)} "
                             "| grep -v '#' | cut -f 9-10 | head -5")))
               #"\n")]
    (remove nil? (map get-ploidy-line lines))))

(defn- get-existing-ploidy
  "Check ploidy in a region, cleanly handling recalling in mixed diploid/haploid (human chrM and sex chromosomes)"
  [vcf-files region]
  (apply max (mapcat #(get-ploidy-file % region) vcf-files)))

(defn by-region
  "Square off a genomic region, identifying variants from all samples and recalling at uncalled positions.
    - Identifies all called variants from all samples
    - For each sample, square off using `sample-by-region`
    - Merge all variant files in the region together."
  [vcf-files bam-files region ref-file dirs out-file config]
  (let [union-vcf (eprep/create-union :gatk vcf-files ref-file region (:union dirs))
        config (assoc config :ploidy (get-existing-ploidy vcf-files region))
        region-square-dir (fsp/safe-mkdir (io/file (:square dirs) (get region :chrom "nochrom")
                                                   (eprep/region->safestr region)))
        region-merge-dir (fsp/safe-mkdir (str region-square-dir "-merge"))
        recall-vcfs (->> (rmap (fn [[sample vcf-file]]
                                 [sample (sample-by-region-prep sample vcf-file (get bam-files sample)
                                                                union-vcf region ref-file region-square-dir config)])
                               (mapcat (fn [vf]
                                         (for [s (vcfutils/get-samples vf)]
                                           [s vf]))
                                       vcf-files) (:cores config))
                         (into [])
                         (sort-by first)
                         (map second))]
    (merge/region-merge :gatk recall-vcfs region ref-file region-merge-dir out-file)))

(defn- sample-to-bam-map*
  "Prepare a map of sample names to BAM files."
  [bam-files ref-file]
  (into {} (mapcat (fn [b]
                     (for [s (case (fs/extension b)
                               ".bam" (bam/sample-names b)
                               ".cram" (cram/sample-names b ref-file))]
                       [s b]))
                   bam-files)))

(defn sample-to-bam-map
  "Prepare a map of sample names to BAM files."
  [bam-files ref-file cache-dir]
  (let [cache-file (fsp/add-file-part (first bam-files) (format "%s-samplemap" (count bam-files))
                                      cache-dir ".edn")]
    (if (itx/needs-run? cache-file)
      (let [bam-map (sample-to-bam-map* bam-files ref-file)]
        (itx/with-tx-file [tx-cache-file cache-file]
          (spit tx-cache-file (pr-str bam-map)))
        (sample-to-bam-map bam-files ref-file cache-dir))
      (read-string (slurp cache-file)))))

(defn- check-versions
  "Ensure we have up to date versions of required software for recalling."
  [config]
  (when (= :freebayes (:caller config))
    (let [version (-> (sh "freebayes")
                      :out
                      (string/split #"\n")
                      last
                      (string/split #":")
                      last
                      string/trim)
          want-version "v0.9.14-1"]
      (when (neg? (version-compare version want-version))
        (throw (Exception. (format "Require at least freebayes %s for recalling. Found %s"
                                   want-version version)))))))

(defn combine-vcfs
  "Combine VCF files with squaring off by recalling at uncalled variant positions."
  [orig-vcf-files bam-files ref-file out-file config]
  (let [dirs {:union (fsp/safe-mkdir (io/file (fs/parent out-file) "union"))
              :square (fsp/safe-mkdir (io/file (fs/parent out-file) "square"))
              :inprep (fsp/safe-mkdir (io/file (fs/parent out-file) "inprep"))}
        bam-map (sample-to-bam-map bam-files ref-file (:inprep dirs))]
    (check-versions config)
    (merge/prep-by-region (fn [vcf-files region merge-dir]
                            (vcfutils/ensure-no-dup-samples vcf-files)
                            (by-region vcf-files bam-map region ref-file
                                       (assoc dirs :merge merge-dir) out-file config))
                          orig-vcf-files ref-file out-file config)))

(defn- usage [options-summary]
  (->> ["Perform squaring off for a set of called VCF files, recalling at no-call positions in each sample."
        ""
        "Usage: bcbio-variation-recall square [options] out-file ref-file [<vcf, bam, cram, or list files>]"
        ""
        "  out-file:    VCF (or bgzipped VCF) file to write merged output to"
        "  ref-file:    FASTA format genome reference file"
        "  <remaining>: VCF files to recall and BAM or CRAM files for each sample. Can be specified "
        "               on the command line or as text files containing paths to files "
        "               for processing. VCFs can be single or multi-sample and BAM/CRAMs can be in "
        "               any order but each VCF sample must have an associated BAM/CRAM file to recall."
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn -main [& args]
  (let [caller-opts #{:freebayes :platypus :samtools}
        {:keys [options arguments errors summary]}
        (parse-opts args [["-c" "--cores CORES" "Number of cores to use" :default 1
                           :parse-fn #(Integer/parseInt %)]
                          ["-m" "--caller CALLER" (str "Calling method to use: "
                                                       (string/join ", " (map name caller-opts)))
                           :default "freebayes"
                           :parse-fn #(keyword %)
                           :validate [#(contains? caller-opts %)
                                      (str "Supported calling options: "
                                           (string/join ", " (map name caller-opts)))]]
                          ["-r" "--region REGION"
                           "Genomic region to subset, in samtools format (chr1:100-200) or BED file"]
                          ["-h" "--help"]])]
    (cond
     (:help options) (clhelp/exit 0 (usage summary))
     errors (clhelp/exit 1 (clhelp/error-msg errors))
     (= 0 (count arguments)) (clhelp/exit 0 (usage summary))
     (< (count arguments) 3) (clhelp/exit 1 (usage summary))
     :else (let [[out-file ref-file & vcf-inputs] arguments
                 arg-files (clhelp/vcf-bam-args vcf-inputs)]
             (cond
              (not (empty? (:missing arg-files)))
              (clhelp/exit 1 (clhelp/error-msg (cons "Input files not found:" (:missing arg-files))))
              (or (not (fs/exists? ref-file)) (not (fs/file? ref-file)))
              (clhelp/exit 1 (clhelp/error-msg [(str "Reference file not found: " ref-file)]))
              :else
              (combine-vcfs (:vcf arg-files) (:bam arg-files) ref-file out-file options))))))
