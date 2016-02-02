import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by martin on 14.11.15.
 */
public class Project {

    @XStreamAsAttribute
    String name;

    @XStreamOmitField
    String localPath;

    @XStreamAlias("url")
    @XStreamAsAttribute
    String remotePath;

    @XStreamOmitField
    Repository localRepo;

    @XStreamOmitField
    Git git;

    @XStreamImplicit
    List<MergeScenario> mergeScenarios;
    @XStreamAsAttribute
    String buildCommand;

    public Project(String localPath, String remotePath, String buildCommand) {
        name = localPath.substring(localPath.lastIndexOf("/") + 1);
        this.localPath = localPath;
        this.remotePath = remotePath;
        this.buildCommand = buildCommand;
        mergeScenarios = new LinkedList<>();
        //init
        try {
            localRepo = new FileRepository(localPath + "/.git");
            git = new Git(localRepo);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Analyses all merges found in the project.
     */
    public void analyse() {
        checkoutMaster();

        List<RevCommit> mergeCommits = getMergeCommits();

        this.mergeScenarios = analyseMergeScenarios(mergeCommits);

        checkoutMaster();
    }

    /**
     * Analyses commits with given commit IDs.
     *
     * @param commitIDs commit IDs of the commits which shall be analysed
     */
    public void analyse(List<String> commitIDs) {
        checkoutMaster();

        List<RevCommit> mergeCommits = getMergeCommits();

        List<RevCommit> mergeCommitsToBeAnalysed = mergeCommits.stream().filter(commit -> commitIDs.contains(commit.getId().name())).collect(Collectors.toCollection(LinkedList::new));
        this.mergeScenarios = analyseMergeScenarios(mergeCommitsToBeAnalysed);

        checkoutMaster();
    }

    /**
     * Analyses the first {@param numberOfAnalysis} commits
     *
     * @param start index to start with
     * @param end   index to end with
     */
    public void analyseFromTo(int start, int end) {
        checkoutMaster();

        List<RevCommit> mergeCommits = getMergeCommits();

        if (mergeCommits.size() != 0) {
            System.out.println(mergeCommits.size() + " merges found");
            //Collections.reverse(mergeCommits);
            this.mergeScenarios = analyseMergeScenarios(mergeCommits.subList(start, end));
        } else {
            System.out.println("No merges found");
        }

        checkoutMaster();
    }

    /**
     * Calculates the number of merges found in this project
     * @return number of mergescenarios.
     */
    public int getNumberOfMerges() {
        checkoutMaster();
        return getMergeCommits().size();
    }

    /**
     * Return the index of a commit in the list of all merge commits.
     *
     * @param commitID ID of commit which index is requested
     * @return index of the commit. Return -1 if there is no such merge commit.
     */
    public int mergeIndexOf(String commitID) {
        checkoutMaster();

        List<RevCommit> mergeCommits = getMergeCommits();

        for (int i = 0; i < mergeCommits.size(); i++) {
            if (mergeCommits.get(i).getId().getName().equals(commitID)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Analyses a given List of RevCommits which are merges.
     * @param mergeCommits JGit RevCommits to analyse
     * @return list of analysed MergeScenarios
     */
    public List<MergeScenario> analyseMergeScenarios(List<RevCommit> mergeCommits) {
        System.out.println("Analysing " + mergeCommits.size() + " merges");
        List<MergeScenario> mergeScenarios = new ArrayList<>(mergeCommits.size());
        for (int i = 0; i < mergeCommits.size(); i++) {
            RevCommit commit = mergeCommits.get(i);
            MergeScenario mergeScenario = analyseMergeScenario(commit);
            mergeScenarios.add(mergeScenario);

            System.out.println("Finished " + (i + 1) + "/" + mergeCommits.size() + "   " + commit.getId().getName());
        }
        return mergeScenarios;
    }

    /**
     * Analyses one given RevCommit which is a merge.
     * @param mergeCommit JGit RevCommit to analyse
     * @return analysed MergeScenario
     */
    public MergeScenario analyseMergeScenario(RevCommit mergeCommit) {
        MergeScenario mergeScenario = new MergeScenario(
                mergeCommit.getName(), mergeCommit.getParents()[0].getName(), mergeCommit.getParents()[1].getName());

        checkoutMaster();

        //Merge
        mergeScenario.setMerge(merge(mergeCommit));

        //Build
        mergeScenario.setBuild(build());

        //Tests

        return mergeScenario;
    }

    /**
     * Returns all commits of the project which are merges.
     * @return all commits which are merges
     */
    public List<RevCommit> getMergeCommits() {
        List<RevCommit> merges = new LinkedList<>();
        Iterable<RevCommit> log;
        try {
            log = git.log().call();
            for (RevCommit comm : log) {
                if (comm.getParentCount() > 1) {
                    merges.add(comm);
                }
            }
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
        return merges;
    }

    /**
     * Performs a merge between the two parents of the given commit and checks for confilicts.
     * Changes the state of the local repo!
     * @param mergeCommit commit, which merge should be performed
     * @return analysis of the merge: conflicts
     */
    public Merge merge(RevCommit mergeCommit) {
        Merge merge = new Merge();
        try {
            git.checkout().setName(mergeCommit.getParents()[0].getName()).call();
            MergeResult mergeResult = git.merge().include(mergeCommit.getParents()[1]).call();
            merge.setState(mergeResult.getMergeStatus().name());

            if (mergeResult.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)) {
                Set<String> keySet = mergeResult.getConflicts().keySet();
                Set<String> conflicts = new HashSet<>(keySet);
                merge.setConflicts(conflicts);
            }
        } catch (GitAPIException e) {
            e.printStackTrace();
            merge.setState("GitAPI Exception");
        }

        return merge;
    }

    /**
     * Builds the project according to a build-script, which has been set previously.
     * @return analysis of the build: state (success/fail), runtime
     */
    public Build build() {
        Process p2;
        Build build = new Build();
        try {
            p2 = Runtime.getRuntime().exec(buildCommand);
            p2.waitFor();
            String buildMessage = org.apache.commons.io.IOUtils.toString(p2.getInputStream());

            //Build Message
            build.setState("UNKNOWN");
            if (buildMessage.contains("NO BUILD POSSIBLE")) {
                build.setState("NO BUILD POSSIBLE");
            } else {
                if (buildMessage.contains("BUILD SUCCESSFUL")) {
                    build.setState("SUCCESSFUL");
                }
                if (buildMessage.contains("BUILD FAILED")) {
                    build.setState("FAILED");
                }
            }
            //Runtime
            if (buildMessage.contains("Total time")) {
                String rawTime = buildMessage.substring(buildMessage.lastIndexOf("Total time: ") + 12);
                build.setRuntime(Double.parseDouble(rawTime.split(" ")[0]));
            }
        } catch (IOException e) {
            build.setState("IO Exception");
        } catch (InterruptedException e) {
            build.setState("Interrupted Exception");
        }

        return build;
    }

    /**
     * Resets the repo.
     */
    public void checkoutMaster() {
        try {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/master").call();
            git.checkout().setForce(true).setName("master").call();
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
    }
}