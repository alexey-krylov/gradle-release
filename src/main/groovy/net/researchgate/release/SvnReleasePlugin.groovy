package net.researchgate.release

import java.util.regex.Matcher
import org.gradle.api.GradleException

/**
 * A command-line style SVN client. Requires user has SVN installed locally.
 * @author elberry
 * @author evgenyg
 * Created: Tue Aug 09 23:25:18 PDT 2011
 */
// TODO: Use SVNKit or SubversionJ
class SvnReleasePlugin extends BaseScmPlugin<SvnReleasePluginConvention> {

	private static final String ERROR = 'Commit failed'
	private static final def urlPattern = ~/URL:\s(.*?)(\/(trunk|branches|tags).*?)$/
	private static final def revPattern = ~/Revision:\s(.*?)$/
	private static final def commitPattern = ~/Committed revision\s(.*?)\.$/

	private static final def environment = [LANG: "C", LC_MESSAGES: "C", LC_ALL: ""];

	void init() {
		findSvnUrl()
		project.ext.set('releaseSvnRev', null)
	}

	@Override
	SvnReleasePluginConvention buildConventionInstance() { new SvnReleasePluginConvention() }

	@Override
	void checkCommitNeeded() {
		String out = exec('svn', 'status')
		def changes = []
		def unknown = []
		out.eachLine { line ->
			switch (line?.trim()?.charAt(0)) {
				case '?':
					unknown << line
					break
				default:
					changes << line
					break
			}
		}
		if (changes) {
			warnOrThrow(releaseConvention().failOnCommitNeeded, "You have ${changes.size()} un-commited changes.")
		}
		if (unknown) {
			warnOrThrow(releaseConvention().failOnUnversionedFiles, "You have ${unknown.size()} un-versioned files.")
		}
	}

	@Override
	void checkUpdateNeeded() {
		def props = project.properties
		String svnUrl = props.releaseSvnUrl
		String svnRev = props.initialSvnRev
		String svnRemoteRev = ""
		// svn status -q -u
		String out = exec('svn', 'status', '-q', '-u')
		def missing = []
		out.eachLine { line ->
			switch (line?.trim()?.charAt(0)) {
				case '*':
					missing << line
					break
			}
		}
		if (missing) {
			warnOrThrow(releaseConvention().failOnUpdateNeeded, "You are missing ${missing.size()} changes.")
		}

		out = exec(true, environment, 'svn', 'info', svnUrl)
		out.eachLine { line ->
			Matcher matcher = line =~ revPattern
			if (matcher.matches()) {
				svnRemoteRev = matcher.group(1)
				project.ext.set('releaseRemoteSvnRev', svnRemoteRev)
			}
		}
		if (svnRev != svnRemoteRev) {
			// warn that there's a difference in local revision versus remote
			warnOrThrow(releaseConvention().failOnUpdateNeeded, "Local revision (${svnRev}) does not match remote (${svnRemoteRev}), local revision is used in tag creation.")
		}
	}

	@Override
	void createReleaseTag(String message = "") {
		def props = project.properties
		String svnUrl = props.releaseSvnUrl
		String svnRev = props.releaseSvnRev ?: props.initialSvnRev //release set by commit below when needed, no commit => initial
		String svnRoot = props.releaseSvnRoot
		String svnTag = tagName()

		exec('svn', 'cp', "${svnUrl}@${svnRev}", "${svnRoot}/tags/${svnTag}", '-m', message ?: "Created by Release Plugin: ${svnTag}")
	}

	@Override
	void commit(String message) {
		String out = exec(['svn', 'ci', '-m', message], 'Error committing new version', environment, ERROR)

		// After the firstcommit we need to find the new revision so the tag is made from the correct revision
		if (project.properties.releaseSvnRev == null) {
			out.eachLine { line ->
				Matcher matcher = line =~ commitPattern
				if (matcher.matches()) {
					String revision = matcher.group(1)
					project.ext.set('releaseSvnRev', revision)
				}
			}
		}
	}

	@Override
	void revert() {
		exec(['svn', 'revert', findPropertiesFile().name], 'Error reverting changes made by the release plugin.', ERROR)
	}

	private void findSvnUrl() {
		String out = exec(true, environment, 'svn', 'info')

		out.eachLine { line ->
			Matcher matcher = line =~ urlPattern
			if (matcher.matches()) {
				String svnRoot = matcher.group(1)
				String svnProject = matcher.group(2)
				project.ext.set('releaseSvnRoot', svnRoot)
				project.ext.set('releaseSvnUrl', "$svnRoot$svnProject")
			}
			matcher = line =~ revPattern
			if (matcher.matches()) {
				String revision = matcher.group(1)
				project.ext.set('initialSvnRev', revision)
			}
		}
		if (!project.hasProperty('releaseSvnUrl')) {
			throw new GradleException('Could not determine root SVN url.')
		}
	}
}