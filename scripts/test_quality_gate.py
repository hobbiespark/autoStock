"""Unit tests for the pure parts of quality_gate.py: py -3.13 -m unittest discover -s scripts -p "test_quality_gate.py"."""

import unittest

import quality_gate as g


class AreasTest(unittest.TestCase):
    def test_staged_paths_pick_areas(self):
        self.assertEqual(g.areas_for(['app/src/main/java/A.java']), ['be'])
        self.assertEqual(g.areas_for(['common/build.gradle.kts', 'frontend/src/App.tsx']), ['be', 'fe'])
        self.assertEqual(g.areas_for(['build.gradle.kts']), ['be'])
        self.assertEqual(g.areas_for(['scripts/quality_gate.py']), ['py'])

    def test_documents_and_scripts_alone_check_nothing(self):
        self.assertEqual(g.areas_for(['aiDoc/a.md', 'README.md', 'scripts/backup_db.ps1', 'scripts/fetch_yahoo_daily.py']), [])
        self.assertEqual(g.areas_for([]), [])


class ProductionUrlTest(unittest.TestCase):
    def test_production_ports_are_refused(self):
        self.assertTrue(g.production_url('jdbc:postgresql://127.0.0.1:56434/autostock'))
        self.assertTrue(g.production_url('jdbc:postgresql://localhost:56432/x?ssl=false'))

    def test_other_urls_pass(self):
        self.assertFalse(g.production_url('jdbc:postgresql://127.0.0.1:55438/autostock'))
        self.assertFalse(g.production_url('jdbc:postgresql://127.0.0.1/autostock'))
        self.assertFalse(g.production_url(None))


class DbEnvTest(unittest.TestCase):
    def test_db_tests_required_by_default(self):
        env = g.db_env({'AUTOSTOCK_TEST_DB_URL': 'u'}, no_db=False)
        self.assertEqual(env['AUTOSTOCK_TEST_DB_REQUIRED'], 'true')
        self.assertEqual(env['AUTOSTOCK_TEST_DB_URL'], 'u')

    def test_no_db_disables_db_tests(self):
        env = g.db_env({'AUTOSTOCK_TEST_DB_URL': 'u', 'AUTOSTOCK_TEST_DB_REQUIRED': 'true', 'PATH': 'p'}, no_db=True)
        self.assertNotIn('AUTOSTOCK_TEST_DB_URL', env)
        self.assertNotIn('AUTOSTOCK_TEST_DB_REQUIRED', env)
        self.assertEqual(env['AUTOSTOCK_TEST_DB_DISABLED'], 'true')
        self.assertNotIn('AUTOSTOCK_TEST_DB_DISABLED', g.db_env({'AUTOSTOCK_TEST_DB_DISABLED': 'true'}, no_db=False))
        self.assertEqual(env['PATH'], 'p')


class ResultsTest(unittest.TestCase):
    def test_totals_and_stale_files(self):
        suites = [
            {'tests': 3, 'failures': 1, 'errors': 0, 'skipped': 1, 'timestamp': 100.0},
            {'tests': 2, 'failures': 0, 'errors': 1, 'skipped': 0, 'timestamp': 50.0},
        ]
        total = g.summarize_results(suites, started=90.0)
        self.assertEqual(total, {'tests': 5, 'failed': 2, 'skipped': 1, 'stale': 1})
        self.assertEqual(g.results_line('app', total), '[gate]   app: tests 5, failed 2, skipped 1, stale 1 files from an earlier run')


if __name__ == '__main__':
    unittest.main()
