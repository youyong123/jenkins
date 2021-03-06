// gate.groovy - System patch gating job
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import groovy.json.JsonOutput
import groovy.transform.Field

def on_load(loader){
    project_lib = loader.load_code('libs/stdci_project.groovy')
    stdci_runner_lib = loader.load_code('libs/stdci_runner.groovy')
    dsl_lib = loader.load_code('libs/stdci_dsl.groovy')
}

@Field def available_suits
@Field def available_threads
@Field def build_thread_params
@Field def system_test_project

def loader_main(loader) {
    stage('Analyzing patches') {
        def gate_info = create_gate_info()
        // Global Var.
        build_thread_params = gate_info.builds
        system_test_project = project_lib.new_project(
            name: env.SYSTEM_TESTS_PROJECT,
            branch: gate_info.st_project?.branch,
            clone_url: gate_info.st_project?.url,
            refspec: gate_info.st_project?.refspec ?: 'refs/heads/master',
        )
        println("System tests project: ${system_test_project.name}")

        project_lib.checkout_project(system_test_project)
        available_suits = get_available_suits(system_test_project.clone_dir_name)
        available_threads = get_available_threads(system_test_project)

        print_builds(build_thread_params)
    }
}

def main() {
    stage('Building packages') {
        def threads = [:]
        releases_to_test = [:]
        threads = create_build_threads(build_thread_params, releases_to_test)
        parallel threads
    }
    stage('Wait for merged packages') {
        wait_for_merged_packages(releases_to_test)
    }
    stage('Running test suits') {
        def releases_list = "Will test the following releases and builds:"
        releases_list += releases_to_test.collect { release, builds ->
            def builds_list = builds.collect { "\n  - ${it}" }.join()
            "\n- ${release}:${builds_list}"
        }.join()
        print(releases_list)
        run_test_threads(
            system_test_project, releases_to_test, available_suits,
            available_threads
        )
    }
}

def print_builds(build_thread_params) {
    def build_list = "Will run ${build_thread_params.size()} build(s):"
    build_list += build_thread_params.collect { "\n- ${it[2]}" }.join()
    print(build_list)
}

def create_build_threads(build_thread_params, releases_to_test) {
    def threads = [:]
    for (i = 0; i < build_thread_params.size(); ++i) {
        // build_thread_params have 3 elements inside the list by order of: job
        // run specs for jenkins, ovirt-releases and unique job name to display
        def job_run_spec = build_thread_params[i][0]
        def releases = build_thread_params[i][1]
        def thread_name = build_thread_params[i][2]
        threads[thread_name] = create_build_thread(
            job_run_spec, releases, releases_to_test
        )
    }
    return threads
}

def create_build_thread(job_run_spec, releases, releases_to_test) {
    return {
        job_run_spec['wait'] = true
        build_results = build(job_run_spec)
        releases.each { release ->
            def temp_url = releases_to_test.get(release, [])
            temp_url << build_results.absoluteUrl
            releases_to_test[release] = temp_url
        }
    }
}

def create_gate_info() {
    def gate_info_json = "gate_info.json"
    withEnv(['PYTHONPATH=jenkins']) {
        sh(
            label: 'Analyse tested patches',
            script: """\
                #!/usr/bin/env python
                import json
                from os import environ
                from stdci_libs.ost_build_resolver import create_gate_info
                gate_info = create_gate_info(
                    environ['CHECKED_COMMITS'],
                    environ['SYSTEM_QUEUE_PREFIX'],
                    environ['SYSTEM_TESTS_PROJECT'],
                )

                with open('${gate_info_json}', 'w') as f:
                    json.dump(gate_info, f)
            """.stripIndent()
        )
    }
    def gate_info = readJSON file: gate_info_json
    return gate_info
}

@NonCPS
def get_test_threads(releases_to_test, available_suits, available_threads) {
    def suit_types_to_use = (env?.SYSTEM_TEST_SUIT_TYPES ?: 'basic').tokenize()
    return releases_to_test.collectMany { release, builds ->
        suit_types_to_use.collectMany { suit_type ->
            def extra_sources = builds.collect { "jenkins:${it}\n"}.join('')
            String suit = "${suit_type}_suite_${release}"
            def threads = available_threads.findResults { thread ->
                if(thread.substage == suit) {
                    thread.extra_sources = extra_sources
                    return thread
                }
            }
            if(!(threads.isEmpty())) {
                return threads
            }
            // script has to be of type `String` so looking it up in the
            // `available_suits` Set will work
            String script = "${suit}.sh"
            if(script in available_suits) {
                return [[
                    'stage': "${suit_type}-suit-${release}",
                    'substage': 'default',
                    'distro': 'el7',
                    'arch': 'x86_64',
                    'script': "automation/$script",
                    'runtime_reqs': [
                        'supportnestinglevel': 2,
                        'isolationlevel' : 'container'
                    ],
                    'release_branches': [:],
                    'reporting': ['style': 'stdci'],
                    'timeout': '3h',
                    'extra_sources' : extra_sources
                ]]
            }
            return []
        }
    }
}

def run_test_threads(
    system_test_project, releases_to_test, available_suits, available_threads
) {
    def test_threads = get_test_threads(
        releases_to_test, available_suits, available_threads
    )
    def threads_list = "Will run the following test suits:"
    threads_list += test_threads.collect {
        "\n - ${stdci_runner_lib.get_job_name(it)}"
    }.join()
    print(threads_list)
    def mirrors = mk_mirrors_conf(releases_to_test)
    print("Will use the following mirrors configuration:\n$mirrors")
    stdci_runner_lib.run_std_ci_jobs_with_loader(
        project: system_test_project,
        jobs: test_threads,
        mirrors: mirrors,
    )
}

def get_available_suits(path) {
    def all_suits
    dir(path) {
        all_suits = findFiles(glob: 'automation/*_suite_*.sh').name as Set
    }
    def available_suits_list = "Found ${all_suits.size()} test suit(s):"
    available_suits_list += all_suits.collect { "\n- ${it}" }.join()
    print(available_suits_list)
    return all_suits
}

def get_available_threads(system_test_project) {
    def threads
    withEnv([
        "STD_CI_CLONE_URL=${system_test_project.clone_url}",
        "STD_CI_REFSPEC=${system_test_project.refspec}",
    ]) {
        threads = dsl_lib.parse(system_test_project.clone_dir_name, 'gate').jobs
    }
    def thread_list = "Found ${threads.size()} test thread(s):"
    thread_list += threads.collect { thread ->
        "\n- ${stdci_runner_lib.get_job_name(thread)}"
    }.join()
    print(thread_list)
    return threads
}

def wait_for_merged_packages(releases_to_test) {
    def releases_set = releases_to_test.keySet()
    def builds
    builds = find_running_builds_before(currentBuild.timeInMillis)
    waitUntil {
        builds = remove_done_and_unrelated_builds(builds, releases_set)
        print "Waiting for: ${builds.fullDisplayName.join(', ')}"
        return builds.isEmpty()
    }
}

@NonCPS
def find_running_builds_before(time) {
    return jenkins.model.Jenkins.instance.allItems(
        hudson.model.Job
    ).findResults({ job ->
        if(!(job.name =~ /_standard-on-(merge|ghpush)$/)) {
            return
        }
        return job.builds.findResult { build ->
            if(build.isBuilding() && build.timeInMillis <= time) {
                return new RunWrapper(build, false)
            }
        }
    }) as List
}

@NonCPS
def remove_done_and_unrelated_builds(builds, releases_set) {
    return builds.findAll({ build ->
        build.rawBuild.isBuilding() \
        && build_is_related_to_gate(build, releases_set)
    })
}

@NonCPS
def build_is_related_to_gate(build, releases_set) {
    def params = build.rawBuild.getAction(hudson.model.ParametersAction)
    def gate_deployments = params?.getParameter('GATE_DEPLOYMENTS')?.value
    return gate_deployments != '__none__' \
        && (
            gate_deployments.is(null)
            || releases_set.intersect(gate_deployments.split() as Set)
        )
}

def mk_mirrors_conf(releases_to_test) {
    def os_apps_domain =\
        env.OPENSHIFT_APPS_DOMAIN ?: 'apps.ovirt.org'
    def os_res_base = "https://resources-${env.OPENSHIFT_PROJECT}.$os_apps_domain"
    def gated_repos = releases_to_test.keySet().collect { release ->
        def release_full = "${env.SYSTEM_QUEUE_PREFIX}-$release"
        return [
            "$release_full-tested" as String,
            "$os_res_base/gated-$release_full/all_latest.json" as String
        ]
    }
    def gated_mirrors_data = ['include:before:': gated_repos]
    if(!env.CI_MIRRORS_URL.is(null)) {
        gated_mirrors_data['include:'] = [env.CI_MIRRORS_URL]
    }
    return JsonOutput.toJson(gated_mirrors_data)
}

// We need to return 'this' so the actual pipeline job can invoke functions from
// this script
return this
